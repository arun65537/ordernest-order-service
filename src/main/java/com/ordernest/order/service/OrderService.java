package com.ordernest.order.service;

import com.ordernest.order.client.InventoryClient;
import com.ordernest.order.client.InventoryProductResponse;
import com.ordernest.order.dto.CreateOrderRequest;
import com.ordernest.order.dto.CreateOrderResponse;
import com.ordernest.order.dto.OrderItemResponse;
import com.ordernest.order.dto.OrderResponse;
import com.ordernest.order.entity.CustomerOrder;
import com.ordernest.order.entity.OrderStatus;
import com.ordernest.order.entity.PaymentStatus;
import com.ordernest.order.entity.ShipmentStatus;
import com.ordernest.order.event.OrderCancellationEvent;
import com.ordernest.order.event.OrderCancellationEventType;
import com.ordernest.order.event.PaymentEvent;
import com.ordernest.order.event.PaymentEventType;
import com.ordernest.order.event.ShipmentEvent;
import com.ordernest.order.exception.BadRequestException;
import com.ordernest.order.exception.ResourceNotFoundException;
import com.ordernest.order.messaging.OrderCancellationEventPublisher;
import com.ordernest.order.repository.OrderRepository;
import com.ordernest.order.security.JwtService;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final InventoryClient inventoryClient;
    private final JwtService jwtService;
    private final OrderCancellationEventPublisher orderCancellationEventPublisher;

    @Transactional
    public CreateOrderResponse createOrder(CreateOrderRequest request, String authorization) {
        UUID userId = extractUserIdFromAuthorization(authorization);
        InventoryProductResponse inventoryProduct = inventoryClient.getProductById(request.item().productId(), authorization);
        int available = inventoryProduct.availableQuantity() == null ? 0 : inventoryProduct.availableQuantity();
        int requested = request.item().quantity();

        if (requested > available) {
            throw new BadRequestException("Insufficient inventory. Available: " + available + ", requested: " + requested);
        }

        BigDecimal unitPrice = inventoryProduct.price(); // must be BigDecimal
        BigDecimal totalAmount = unitPrice.multiply(BigDecimal.valueOf(requested));

        int updatedAvailableQuantity = available - requested;
        inventoryClient.updateProductStock(request.item().productId(), updatedAvailableQuantity, authorization);

        CustomerOrder order = new CustomerOrder();
        order.setUserId(userId);
        order.setProductId(request.item().productId());
        order.setProductName(inventoryProduct.name());
        order.setQuantity(requested);
        order.setTotalAmount(totalAmount);
        order.setCurrency(inventoryProduct.currency());
        order.setStatus(OrderStatus.CREATED);
        order.setPaymentStatus(PaymentStatus.PENDING);
        order.setShipmentStatus(ShipmentStatus.NOT_CREATED);

        CustomerOrder saved = orderRepository.save(order);
        return new CreateOrderResponse(saved.getId());
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderById(UUID orderId) {
        return mapToResponse(findById(orderId));
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getMyOrders(String authorization) {
        UUID userId = extractUserIdFromAuthorization(authorization);
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Transactional
    public OrderResponse cancelOrderByUser(UUID orderId, String authorization) {
        UUID userId = extractUserIdFromAuthorization(authorization);
        CustomerOrder order = findById(orderId);

        if (!userId.equals(order.getUserId())) {
            throw new BadRequestException("Order does not belong to authenticated user");
        }

        OrderStatus previousOrderStatus = order.getStatus();

        order.setStatus(OrderStatus.CANCELLED);

        PaymentStatus currentPaymentStatus = order.getPaymentStatus();
        if (currentPaymentStatus == PaymentStatus.SUCCESS) {
            order.setPaymentStatus(PaymentStatus.REFUND_INITIATED);
        } else if (currentPaymentStatus == PaymentStatus.REFUND_INITIATED) {
            order.setPaymentStatus(PaymentStatus.REFUND_INITIATED);
        } else if (currentPaymentStatus == PaymentStatus.REFUNDED) {
            order.setPaymentStatus(PaymentStatus.REFUNDED);
        } else {
            order.setPaymentStatus(PaymentStatus.FAILED);
        }

        ShipmentStatus currentShipmentStatus = order.getShipmentStatus();
        if (currentShipmentStatus == ShipmentStatus.NOT_CREATED) {
            order.setShipmentStatus(ShipmentStatus.NOT_CREATED);
        } else {
            order.setShipmentStatus(ShipmentStatus.RETURNED);
        }

        CustomerOrder saved = orderRepository.save(order);

        if (previousOrderStatus != OrderStatus.CANCELLED) {
            publishOrderCancellationEvent(saved, "User cancelled order");
        }

        return mapToResponse(saved);
    }

    @Transactional
    public void applyPaymentEvent(PaymentEvent paymentEvent) {
        if (paymentEvent == null || paymentEvent.orderId() == null || paymentEvent.eventType() == null) {
            log.warn("Skipping payment event with missing required fields: {}", paymentEvent);
            return;
        }

        UUID orderId;
        try {
            orderId = UUID.fromString(paymentEvent.orderId());
        } catch (IllegalArgumentException ex) {
            log.warn("Skipping payment event with invalid orderId: {}", paymentEvent.orderId());
            return;
        }

        orderRepository.findById(orderId).ifPresentOrElse(
                order -> {
                    if (paymentEvent.paymentId() != null && !paymentEvent.paymentId().isBlank()) {
                        order.setRazorpayPaymentId(paymentEvent.paymentId());
                    }

                    if (paymentEvent.eventType() == PaymentEventType.PAYMENT_SUCCESS) {
                        order.setStatus(OrderStatus.CONFIRMED);
                        order.setPaymentStatus(PaymentStatus.SUCCESS);
                    } else if (paymentEvent.eventType() == PaymentEventType.PAYMENT_FAILED) {
                        order.setStatus(OrderStatus.CANCELLED);
                        order.setPaymentStatus(PaymentStatus.FAILED);
                        order.setShipmentStatus(ShipmentStatus.NOT_CREATED);
                    }

                    orderRepository.save(order);
                },
                () -> log.warn("Payment event received for unknown orderId: {}", orderId)
        );
    }

    @Transactional
    public void applyShipmentEvent(ShipmentEvent shipmentEvent) {
        if (shipmentEvent == null || shipmentEvent.orderId() == null || shipmentEvent.shipmentStatus() == null) {
            log.warn("Skipping shipment event with missing required fields: {}", shipmentEvent);
            return;
        }

        UUID orderId;
        try {
            orderId = UUID.fromString(shipmentEvent.orderId());
        } catch (IllegalArgumentException ex) {
            log.warn("Skipping shipment event with invalid orderId: {}", shipmentEvent.orderId());
            return;
        }

        orderRepository.findById(orderId).ifPresentOrElse(
                order -> {
                    ShipmentStatus current = order.getShipmentStatus();
                    ShipmentStatus next = shipmentEvent.shipmentStatus();

                    if (current == next) {
                        return;
                    }

                    if (order.getStatus() != OrderStatus.CONFIRMED || order.getPaymentStatus() != PaymentStatus.SUCCESS) {
                        log.warn("Skipping shipment event because order is not in CONFIRMED/SUCCESS state. orderId={}, status={}, paymentStatus={}",
                                orderId, order.getStatus(), order.getPaymentStatus());
                        return;
                    }

                    boolean validTransition =
                            (current == ShipmentStatus.NOT_CREATED && next == ShipmentStatus.CREATED)
                                    || (current == ShipmentStatus.CREATED && next == ShipmentStatus.SHIPPED)
                                    || (current == ShipmentStatus.SHIPPED && next == ShipmentStatus.DELIVERED)
                                    || (current == ShipmentStatus.DELIVERED && next == ShipmentStatus.RETURNED);

                    if (!validTransition) {
                        log.warn("Skipping invalid shipment transition. orderId={}, current={}, next={}", orderId, current, next);
                        return;
                    }

                    order.setShipmentStatus(next);
                    if (next == ShipmentStatus.RETURNED) {
                        order.setStatus(OrderStatus.CANCELLED);
                        order.setPaymentStatus(PaymentStatus.REFUNDED);
                    }
                    orderRepository.save(order);

                    if (next == ShipmentStatus.RETURNED) {
                        publishOrderCancellationEvent(order, "Shipment returned");
                    }
                },
                () -> log.warn("Shipment event received for unknown orderId: {}", orderId)
        );
    }

    private void publishOrderCancellationEvent(CustomerOrder order, String reason) {
        OrderCancellationEvent event = new OrderCancellationEvent(
                order.getProductId(),
                order.getQuantity(),
                order.getId().toString(),
                OrderCancellationEventType.CANCALLED,
                reason,
                Instant.now()
        );
        orderCancellationEventPublisher.publish(event);
    }

    private CustomerOrder findById(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));
    }

    private OrderResponse mapToResponse(CustomerOrder order) {
        return new OrderResponse(
                order.getId(),
                new OrderItemResponse(order.getProductId(), order.getProductName(), order.getQuantity(), order.getTotalAmount(), order.getCurrency()),
                order.getStatus(),
                order.getPaymentStatus(),
                order.getRazorpayPaymentId(),
                order.getShipmentStatus(),
                order.getCreatedAt()
        );
    }

    private UUID extractUserIdFromAuthorization(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BadRequestException("Missing or invalid Authorization header");
        }

        String token = authorization.substring(7);
        try {
            UUID userId = jwtService.extractUserId(token);
            if (userId == null) {
                throw new BadRequestException("userId not found in token");
            }
            return userId;
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Invalid userId in token");
        } catch (Exception ex) {
            throw new BadRequestException("Unable to resolve userId from token");
        }
    }
}

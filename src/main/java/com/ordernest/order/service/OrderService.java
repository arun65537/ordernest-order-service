package com.ordernest.order.service;

import com.ordernest.order.client.InventoryClient;
import com.ordernest.order.client.InventoryProductResponse;
import com.ordernest.order.dto.CreateOrderRequest;
import com.ordernest.order.dto.CreateOrderResponse;
import com.ordernest.order.dto.OrderEventHistoryResponse;
import com.ordernest.order.dto.OrderItemResponse;
import com.ordernest.order.dto.OrderResponse;
import com.ordernest.order.dto.UpdateShipmentStatusRequest;
import com.ordernest.order.entity.CustomerOrder;
import com.ordernest.order.entity.OrderEventHistory;
import com.ordernest.order.entity.OrderEventType;
import com.ordernest.order.entity.OrderStatus;
import com.ordernest.order.entity.PaymentStatus;
import com.ordernest.order.entity.ShipmentStatus;
import com.ordernest.order.event.OrderStatusChangedEvent;
import com.ordernest.order.event.PaymentEvent;
import com.ordernest.order.event.PaymentEventType;
import com.ordernest.order.exception.BadRequestException;
import com.ordernest.order.exception.ResourceNotFoundException;
import com.ordernest.order.messaging.OrderStatusChangedEventPublisher;
import com.ordernest.order.repository.OrderEventHistoryRepository;
import com.ordernest.order.repository.OrderRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderEventHistoryRepository orderEventHistoryRepository;
    private final InventoryClient inventoryClient;
    private final OrderStatusChangedEventPublisher orderStatusChangedEventPublisher;
    private final ObjectMapper objectMapper;

    @Transactional
    public CreateOrderResponse createOrder(CreateOrderRequest request, String userIdHeader, String userEmailHeader) {
        UUID userId = extractUserId(userIdHeader);
        String userEmail = resolveUserEmail(userEmailHeader);
        InventoryProductResponse inventoryProduct = inventoryClient.getProductById(request.item().productId(), null);
        int available = inventoryProduct.availableQuantity() == null ? 0 : inventoryProduct.availableQuantity();
        int requested = request.item().quantity();

        if (requested > available) {
            throw new BadRequestException("Insufficient inventory. Available: " + available + ", requested: " + requested);
        }

        BigDecimal unitPrice = inventoryProduct.price(); // must be BigDecimal
        BigDecimal totalAmount = unitPrice.multiply(BigDecimal.valueOf(requested));

        int updatedAvailableQuantity = available - requested;
        inventoryClient.updateProductStock(request.item().productId(), updatedAvailableQuantity, null);

        CustomerOrder order = new CustomerOrder();
        order.setUserId(userId);
        order.setUserEmail(userEmail);
        order.setProductId(request.item().productId());
        order.setProductName(inventoryProduct.name());
        order.setQuantity(requested);
        order.setTotalAmount(totalAmount);
        order.setCurrency(inventoryProduct.currency());
        order.setStatus(OrderStatus.CREATED);
        order.setPaymentStatus(PaymentStatus.UNPAID);
        order.setShipmentStatus(ShipmentStatus.NOT_CREATED);

        CustomerOrder saved = orderRepository.save(order);
        publishOrderStatusChanged(saved, null, "Order created");
        return new CreateOrderResponse(saved.getId());
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderById(UUID orderId) {
        return mapToResponse(findById(orderId));
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getMyOrders(String userIdHeader) {
        UUID userId = extractUserId(userIdHeader);
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<OrderEventHistoryResponse> getOrderEvents(UUID orderId, String userIdHeader, String userRolesHeader) {
        CustomerOrder order = findById(orderId);
        boolean admin = isAdmin(userRolesHeader);
        if (!admin) {
            UUID userId = extractUserId(userIdHeader);
            if (!userId.equals(order.getUserId())) {
                throw new AccessDeniedException("Only owner or admin can view order events");
            }
        }

        return orderEventHistoryRepository.findByOrderIdOrderByOccurredAtAsc(orderId)
                .stream()
                .map(event -> new OrderEventHistoryResponse(
                        event.getId(),
                        event.getOrderId(),
                        event.getUserId(),
                        event.getEventType(),
                        event.getPreviousStatus(),
                        event.getCurrentStatus(),
                        event.getActor(),
                        event.getReason(),
                        event.getPayloadJson(),
                        event.getOccurredAt()
                ))
                .toList();
    }

    @Transactional
    public OrderResponse cancelOrderByUser(UUID orderId, String userIdHeader, String userEmailHeader) {
        UUID userId = extractUserId(userIdHeader);
        CustomerOrder order = findById(orderId);
        if ((order.getUserEmail() == null || order.getUserEmail().isBlank()) && userEmailHeader != null && !userEmailHeader.isBlank()) {
            order.setUserEmail(resolveUserEmail(userEmailHeader));
        }

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
            order.setPaymentStatus(PaymentStatus.UNPAID);
        }

        ShipmentStatus currentShipmentStatus = order.getShipmentStatus();
        if (currentShipmentStatus == ShipmentStatus.NOT_CREATED) {
            order.setShipmentStatus(ShipmentStatus.NOT_CREATED);
        } else {
            order.setShipmentStatus(ShipmentStatus.RETURNED);
        }

        CustomerOrder saved = orderRepository.save(order);

        if (previousOrderStatus != OrderStatus.CANCELLED) {
            publishOrderStatusChanged(saved, previousOrderStatus, "User cancelled order");
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
                    OrderStatus previousStatus = order.getStatus();
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
                    } else if (paymentEvent.eventType() == PaymentEventType.PAYMENT_REFUNDED) {
                        order.setStatus(OrderStatus.CANCELLED);
                        order.setPaymentStatus(PaymentStatus.REFUNDED);
                    }

                    CustomerOrder saved = orderRepository.save(order);
                    recordOrderEvent(
                            saved,
                            resolvePaymentEventType(paymentEvent.eventType()),
                            previousStatus,
                            saved.getStatus(),
                            "payment-service",
                            resolvePaymentReason(paymentEvent),
                            Map.of(
                                    "paymentStatus", saved.getPaymentStatus().name(),
                                    "shipmentStatus", saved.getShipmentStatus().name(),
                                    "paymentId", saved.getRazorpayPaymentId() == null ? "" : saved.getRazorpayPaymentId()
                            )
                    );
                    publishOrderStatusChanged(saved, previousStatus, resolvePaymentReason(paymentEvent));
                },
                () -> log.warn("Payment event received for unknown orderId: {}", orderId)
        );
    }

    @Transactional
    public OrderResponse updateShipmentStatusByAdmin(UpdateShipmentStatusRequest request, String userRolesHeader, String userEmailHeader) {
        if (!isAdmin(userRolesHeader)) {
            throw new AccessDeniedException("Only admin can update shipment status");
        }

        validateShipmentUpdateRequest(request);
        UUID orderId = parseOrderId(request.orderId());

        CustomerOrder order = findById(orderId);
        String actor = resolveActorEmail(userEmailHeader);
        ShipmentStatus current = order.getShipmentStatus();
        ShipmentStatus next = request.shipmentStatus();

        if (current == next) {
            return mapToResponse(order);
        }

        ensureOrderReadyForShipmentUpdate(order);
        ensureValidShipmentTransition(current, next);

        order.setShipmentStatus(next);

        boolean returned = next == ShipmentStatus.RETURNED;
        OrderStatus previousStatus = order.getStatus();
        if (returned) {
            order.setStatus(OrderStatus.CANCELLED);
            order.setPaymentStatus(PaymentStatus.REFUNDED);
        }

        CustomerOrder saved = orderRepository.save(order);
        recordOrderEvent(
                saved,
                resolveShipmentEventType(next),
                previousStatus,
                saved.getStatus(),
                actor,
                "Shipment status updated by admin",
                Map.of("shipmentStatus", next.name())
        );

        if (returned) {
            publishOrderStatusChanged(saved, previousStatus, "Shipment returned");
        }

        return mapToResponse(saved);
    }

    private void validateShipmentUpdateRequest(UpdateShipmentStatusRequest request) {
        if (request == null || request.orderId() == null || request.shipmentStatus() == null) {
            throw new BadRequestException("orderId and shipmentStatus are required");
        }
    }

    private UUID parseOrderId(String orderIdRaw) {
        try {
            return UUID.fromString(orderIdRaw.trim());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Invalid orderId");
        }
    }

    private void ensureOrderReadyForShipmentUpdate(CustomerOrder order) {
        if (order.getStatus() != OrderStatus.CONFIRMED || order.getPaymentStatus() != PaymentStatus.SUCCESS) {
            throw new BadRequestException("Order must be CONFIRMED with successful payment before shipment updates");
        }
    }

    private void ensureValidShipmentTransition(ShipmentStatus current, ShipmentStatus next) {
        boolean validTransition =
                (current == ShipmentStatus.NOT_CREATED && next == ShipmentStatus.CREATED)
                        || (current == ShipmentStatus.CREATED && next == ShipmentStatus.SHIPPED)
                        || (current == ShipmentStatus.SHIPPED && next == ShipmentStatus.DELIVERED)
                        || (current == ShipmentStatus.DELIVERED && next == ShipmentStatus.RETURNED);

        if (!validTransition) {
            throw new BadRequestException("Invalid shipment transition from " + current + " to " + next);
        }
    }

    private void publishOrderStatusChanged(CustomerOrder order, OrderStatus previousStatus, String reason) {
        if (previousStatus != null && previousStatus == order.getStatus()) {
            return;
        }

        OrderStatusChangedEvent event = new OrderStatusChangedEvent(
                order.getId().toString(),
                order.getUserId(),
                order.getUserEmail(),
                order.getProductId(),
                order.getProductName(),
                order.getQuantity(),
                order.getTotalAmount(),
                order.getCurrency(),
                previousStatus,
                order.getStatus(),
                order.getPaymentStatus(),
                order.getShipmentStatus(),
                reason,
                Instant.now()
        );
        orderStatusChangedEventPublisher.publish(event);
        recordOrderEvent(
                order,
                OrderEventType.ORDER_STATUS_CHANGED,
                previousStatus,
                order.getStatus(),
                "order-service",
                reason,
                Map.of(
                        "paymentStatus", order.getPaymentStatus().name(),
                        "shipmentStatus", order.getShipmentStatus().name()
                )
        );
    }

    private OrderEventType resolvePaymentEventType(PaymentEventType paymentEventType) {
        return switch (paymentEventType) {
            case PAYMENT_SUCCESS -> OrderEventType.PAYMENT_SUCCESS;
            case PAYMENT_FAILED -> OrderEventType.PAYMENT_FAILED;
            case PAYMENT_REFUNDED -> OrderEventType.PAYMENT_REFUNDED;
        };
    }

    private OrderEventType resolveShipmentEventType(ShipmentStatus shipmentStatus) {
        return switch (shipmentStatus) {
            case CREATED -> OrderEventType.SHIPMENT_CREATED;
            case SHIPPED -> OrderEventType.SHIPMENT_SHIPPED;
            case DELIVERED -> OrderEventType.SHIPMENT_DELIVERED;
            case RETURNED -> OrderEventType.SHIPMENT_RETURNED;
            case NOT_CREATED -> OrderEventType.SHIPMENT_CREATED;
        };
    }

    private void recordOrderEvent(
            CustomerOrder order,
            OrderEventType eventType,
            OrderStatus previousStatus,
            OrderStatus currentStatus,
            String actor,
            String reason,
            Map<String, Object> payload
    ) {
        OrderEventHistory event = new OrderEventHistory();
        event.setOrderId(order.getId());
        event.setUserId(order.getUserId());
        event.setEventType(eventType.name());
        event.setPreviousStatus(previousStatus);
        event.setCurrentStatus(currentStatus);
        event.setActor(actor);
        event.setReason(reason);
        event.setPayloadJson(toJson(payload));
        event.setOccurredAt(Instant.now());
        orderEventHistoryRepository.save(event);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize order event payload, storing fallback text", ex);
            return payload.toString();
        }
    }

    private String resolvePaymentReason(PaymentEvent paymentEvent) {
        if (paymentEvent.reason() != null && !paymentEvent.reason().isBlank()) {
            return paymentEvent.reason();
        }
        return switch (paymentEvent.eventType()) {
            case PAYMENT_SUCCESS -> "Payment completed successfully";
            case PAYMENT_FAILED -> "Payment failed";
            case PAYMENT_REFUNDED -> "Payment refunded";
        };
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

    private UUID extractUserId(String userIdHeader) {
        if (userIdHeader == null || userIdHeader.isBlank()) {
            throw new BadRequestException("Missing user identity header");
        }
        try {
            return UUID.fromString(userIdHeader.trim());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Invalid user identity header");
        }
    }

    private boolean isAdmin(String userRolesHeader) {
        if (userRolesHeader == null || userRolesHeader.isBlank()) {
            return false;
        }

        return List.of(userRolesHeader.split(","))
                .stream()
                .map(String::trim)
                .map(this::normalizeRole)
                .anyMatch("ROLE_ADMIN"::equals);
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "";
        }

        String trimmedRole = role.trim().toUpperCase(Locale.ROOT);
        if (!trimmedRole.startsWith("ROLE_")) {
            trimmedRole = "ROLE_" + trimmedRole;
        }
        return trimmedRole;
    }

    private String resolveActorEmail(String userEmailHeader) {
        if (userEmailHeader == null || userEmailHeader.isBlank()) {
            return "api-gateway";
        }
        return userEmailHeader.trim();
    }

    private String resolveUserEmail(String userEmailHeader) {
        if (userEmailHeader == null || userEmailHeader.isBlank()) {
            return null;
        }
        return userEmailHeader.trim();
    }
}

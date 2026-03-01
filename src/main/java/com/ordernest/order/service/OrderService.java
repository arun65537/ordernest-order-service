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
import com.ordernest.order.event.PaymentEvent;
import com.ordernest.order.event.PaymentEventType;
import com.ordernest.order.exception.BadRequestException;
import com.ordernest.order.exception.ResourceNotFoundException;
import com.ordernest.order.repository.OrderRepository;
import com.ordernest.order.security.JwtService;

import java.math.BigDecimal;
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

        CustomerOrder saved = orderRepository.save(order);
        return new CreateOrderResponse(saved.getId());
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderById(UUID orderId) {
        return mapToResponse(findById(orderId));
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getOrdersByUserId(UUID userId) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::mapToResponse)
                .toList();
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
                    if (paymentEvent.eventType() == PaymentEventType.PAYMENT_SUCCESS) {
                        order.setStatus(OrderStatus.CONFIRMED);
                        order.setPaymentStatus(PaymentStatus.SUCCESS);
                    } else if (paymentEvent.eventType() == PaymentEventType.PAYMENT_FAILED) {
                        order.setStatus(OrderStatus.CANCELLED);
                        order.setPaymentStatus(PaymentStatus.FAILED);
                    }

                    orderRepository.save(order);
                },
                () -> log.warn("Payment event received for unknown orderId: {}", orderId)
        );
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

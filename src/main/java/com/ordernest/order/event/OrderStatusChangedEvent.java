package com.ordernest.order.event;

import com.ordernest.order.entity.OrderStatus;
import com.ordernest.order.entity.PaymentStatus;
import com.ordernest.order.entity.ShipmentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderStatusChangedEvent(
        String orderId,
        UUID userId,
        String userEmail,
        UUID productId,
        String productName,
        Integer quantity,
        BigDecimal totalAmount,
        String currency,
        OrderStatus previousStatus,
        OrderStatus currentStatus,
        PaymentStatus paymentStatus,
        ShipmentStatus shipmentStatus,
        String reason,
        Instant timestamp
) {
}

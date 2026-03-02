package com.ordernest.order.dto;

import com.ordernest.order.entity.OrderStatus;
import com.ordernest.order.entity.PaymentStatus;
import com.ordernest.order.entity.ShipmentStatus;
import java.time.Instant;
import java.util.UUID;

public record OrderResponse(
        UUID orderId,
        OrderItemResponse item,
        OrderStatus status,
        PaymentStatus paymentStatus,
        ShipmentStatus shipmentStatus,
        Instant createdAt
) {
}

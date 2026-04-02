package com.ordernest.order.dto;

import com.ordernest.order.entity.OrderStatus;
import java.time.Instant;
import java.util.UUID;

public record OrderEventHistoryResponse(
        UUID id,
        UUID orderId,
        UUID userId,
        String eventType,
        OrderStatus previousStatus,
        OrderStatus currentStatus,
        String actor,
        String reason,
        String payloadJson,
        Instant occurredAt
) {}

package com.ordernest.order.event;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentEvent(
        BigDecimal amount,
        String currency,
        PaymentEventType eventType,
        String orderId,
        String paymentId,
        String reason,
        Instant timestamp
) {
}

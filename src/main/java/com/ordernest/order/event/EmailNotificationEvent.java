package com.ordernest.order.event;

import java.time.Instant;

public record EmailNotificationEvent(
        String to,
        String subject,
        String body,
        String eventType,
        Instant timestamp
) {}

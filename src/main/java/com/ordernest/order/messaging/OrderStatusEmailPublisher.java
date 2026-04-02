package com.ordernest.order.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordernest.order.entity.CustomerOrder;
import com.ordernest.order.entity.OrderStatus;
import com.ordernest.order.event.EmailNotificationEvent;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatusEmailPublisher {

    private static final String EVENT_TYPE = "ORDER_STATUS_CHANGED";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.kafka.topic.email-events:notification.email.events}")
    private String emailEventsTopic;

    public void publish(CustomerOrder order, OrderStatus previousStatus, String reason) {
        if (order.getUserEmail() == null || order.getUserEmail().isBlank()) {
            log.debug("Skipping order status email because recipient email is missing for orderId={}", order.getId());
            return;
        }

        String subject = "Order " + order.getId() + " status: " + order.getStatus();
        String body = """
                Your order status has changed.
                Order ID: %s
                Previous status: %s
                Current status: %s
                Payment status: %s
                Shipment status: %s
                Reason: %s
                Product: %s
                Quantity: %s
                Total amount: %s %s
                """.formatted(
                order.getId(),
                previousStatus == null ? "N/A" : previousStatus,
                order.getStatus(),
                order.getPaymentStatus(),
                order.getShipmentStatus(),
                reason == null || reason.isBlank() ? "N/A" : reason,
                order.getProductName() == null ? "N/A" : order.getProductName(),
                order.getQuantity(),
                order.getTotalAmount(),
                order.getCurrency() == null ? "" : order.getCurrency()
        );

        EmailNotificationEvent event = new EmailNotificationEvent(
                order.getUserEmail(),
                subject,
                body,
                EVENT_TYPE,
                Instant.now()
        );

        final String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize order status email event for orderId={}", order.getId(), ex);
            return;
        }

        kafkaTemplate.send(emailEventsTopic, order.getUserEmail(), payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish order status email for orderId={}", order.getId(), ex);
                    } else {
                        log.info("Published order status email event. topic={}, partition={}, offset={}, orderId={}",
                                result.getRecordMetadata().topic(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset(),
                                order.getId());
                    }
                });
    }
}

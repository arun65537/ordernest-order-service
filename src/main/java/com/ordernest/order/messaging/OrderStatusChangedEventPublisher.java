package com.ordernest.order.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordernest.order.event.OrderStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatusChangedEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.kafka.topic.order-status-events:order.status.events}")
    private String orderStatusEventsTopic;

    public void publish(OrderStatusChangedEvent event) {
        final String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize unified order status event for orderId={}", event.orderId(), ex);
            return;
        }

        kafkaTemplate.send(orderStatusEventsTopic, event.orderId(), payload)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish unified order status event for orderId={}", event.orderId(), ex);
                    } else {
                        log.info("Unified order status event published. topic={}, partition={}, offset={}, orderId={}",
                                result.getRecordMetadata().topic(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset(),
                                event.orderId());
                    }
                });
    }
}

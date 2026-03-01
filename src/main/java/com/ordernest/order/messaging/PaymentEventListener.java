package com.ordernest.order.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordernest.order.event.PaymentEvent;
import com.ordernest.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private final ObjectMapper objectMapper;
    private final OrderService orderService;

    @KafkaListener(
            topics = "${app.kafka.topic.payment-events}",
            groupId = "${app.kafka.consumer.group-id}"
    )
    public void onPaymentEvent(String payload) {
        try {
            PaymentEvent paymentEvent = objectMapper.readValue(payload, PaymentEvent.class);
            orderService.applyPaymentEvent(paymentEvent);
        } catch (JsonProcessingException ex) {
            log.error("Failed to parse payment event payload: {}", payload, ex);
        } catch (Exception ex) {
            log.error("Failed to process payment event payload: {}", payload, ex);
        }
    }
}

package com.ordernest.order.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordernest.order.event.ShipmentEvent;
import com.ordernest.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ShipmentEventListener {

    private final ObjectMapper objectMapper;
    private final OrderService orderService;

    @KafkaListener(
            topics = "${app.kafka.topic.shipment-events}",
            groupId = "${app.kafka.consumer.shipment-group-id}"
    )
    public void onShipmentEvent(String payload) {
        try {
            ShipmentEvent shipmentEvent = objectMapper.readValue(payload, ShipmentEvent.class);
            orderService.applyShipmentEvent(shipmentEvent);
        } catch (JsonProcessingException ex) {
            log.error("Failed to parse shipment event payload: {}", payload, ex);
        } catch (Exception ex) {
            log.error("Failed to process shipment event payload: {}", payload, ex);
        }
    }
}

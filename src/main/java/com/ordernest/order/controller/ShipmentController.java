package com.ordernest.order.controller;

import com.ordernest.order.dto.OrderResponse;
import com.ordernest.order.dto.UpdateShipmentStatusRequest;
import com.ordernest.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shipments")
@RequiredArgsConstructor
public class ShipmentController {

    private final OrderService orderService;

    @PostMapping("/status")
    public ResponseEntity<OrderResponse> updateShipmentStatus(
            @Valid @RequestBody UpdateShipmentStatusRequest request,
            @RequestHeader(value = "X-User-Roles", required = false) String userRoles,
            @RequestHeader(value = "X-User-Email", required = false) String userEmail
    ) {
        return ResponseEntity.ok(orderService.updateShipmentStatusByAdmin(request, userRoles, userEmail));
    }
}

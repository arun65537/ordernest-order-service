package com.ordernest.order.controller;

import com.ordernest.order.dto.CreateOrderRequest;
import com.ordernest.order.dto.CreateOrderResponse;
import com.ordernest.order.dto.OrderResponse;
import com.ordernest.order.service.OrderService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<CreateOrderResponse> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.createOrder(request, userId));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrderById(
            @PathVariable UUID orderId
    ) {
        return ResponseEntity.ok(orderService.getOrderById(orderId));
    }

    @GetMapping("/me")
    public ResponseEntity<List<OrderResponse>> getMyOrders(
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return ResponseEntity.ok(orderService.getMyOrders(userId));
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<OrderResponse> cancelOrder(
            @PathVariable UUID orderId,
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return ResponseEntity.ok(orderService.cancelOrderByUser(orderId, userId));
    }
}

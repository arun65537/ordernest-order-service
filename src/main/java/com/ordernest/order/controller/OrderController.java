package com.ordernest.order.controller;

import com.ordernest.order.dto.CreateOrderRequest;
import com.ordernest.order.dto.CreateOrderResponse;
import com.ordernest.order.dto.OrderEventHistoryResponse;
import com.ordernest.order.dto.OrderResponse;
import com.ordernest.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Orders", description = "Order management endpoints")
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @Operation(summary = "Create order")
    public ResponseEntity<CreateOrderResponse> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Email", required = false) String userEmail
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.createOrder(request, userId, userEmail));
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Get order by id")
    public ResponseEntity<OrderResponse> getOrderById(
            @PathVariable UUID orderId
    ) {
        return ResponseEntity.ok(orderService.getOrderById(orderId));
    }

    @GetMapping("/{orderId}/events")
    @Operation(summary = "Get order event history by order id")
    public ResponseEntity<List<OrderEventHistoryResponse>> getOrderEvents(
            @PathVariable UUID orderId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Roles", required = false) String userRoles
    ) {
        return ResponseEntity.ok(orderService.getOrderEvents(orderId, userId, userRoles));
    }

    @GetMapping("/me")
    @Operation(summary = "Get logged-in user orders")
    public ResponseEntity<List<OrderResponse>> getMyOrders(
            @RequestHeader(value = "X-User-Id", required = false) String userId
    ) {
        return ResponseEntity.ok(orderService.getMyOrders(userId));
    }

    @PostMapping("/{orderId}/cancel")
    @Operation(summary = "Cancel order by user")
    public ResponseEntity<OrderResponse> cancelOrder(
            @PathVariable UUID orderId,
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-User-Email", required = false) String userEmail
    ) {
        return ResponseEntity.ok(orderService.cancelOrderByUser(orderId, userId, userEmail));
    }
}

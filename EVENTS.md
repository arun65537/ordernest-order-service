# Kafka Events

This document is the current Kafka event contract reference for OrderNest services.

## Topics

### `order.status.events`
- Producer: `ordernest-order-service`
- Consumers:
  - `ordernest-inventory-service` (`OrderStatusChangedEventListener`)
  - `notification-service` (`OrderStatusChangedEmailConsumer`)
- Purpose:
  - Single event stream for order state transitions.
  - Inventory stock release when an order transitions to `CANCELLED`.
  - Order status email notifications.
- Key: `orderId`
- Payload shape (`OrderStatusChangedEvent`):
  - `orderId` (string)
  - `userId` (UUID)
  - `userEmail` (string, optional)
  - `productId` (UUID)
  - `productName` (string)
  - `quantity` (integer)
  - `totalAmount` (decimal)
  - `currency` (string)
  - `previousStatus` (`OrderStatus`)
  - `currentStatus` (`OrderStatus`)
  - `paymentStatus` (`PaymentStatus`)
  - `shipmentStatus` (`ShipmentStatus`)
  - `reason` (string)
  - `timestamp` (instant)

### `payment.events`
- Producer: `ordernest-payment-service`
- Consumers:
  - `ordernest-order-service` (`PaymentEventListener`)
- Purpose:
  - Payment lifecycle updates consumed by order service to update order/payment status.

### `sso.action.event`
- Producer: `sso-service`
- Consumers:
  - `notification-service` (`AuthEmailEventConsumer`)
- Purpose:
  - Data-only SSO user action events (verification/reset/password-change actions).
  - Notification service builds subject and HTML templates from event data.

## Notes
- `ordernest-order-service` now emits one Kafka event topic for order status updates: `order.status.events`.
- `notification-service` owns email HTML generation for both:
  - order status notifications (`order.status.events`)
  - sso action notifications (`sso.action.event`)

# Order Service

Spring Boot order service for OrderNest.

Local URL: `http://localhost:8082`  
Live URL: `https://ordernest-order-service.onrender.com`  
API Gateway URL: `https://ordernest-api-gateway.onrender.com`

## What it does
- Create and fetch orders
- Cancel orders
- Update shipment status (admin path)
- Record full order event history and expose it via API

## Configuration
`src/main/resources/application.yml`

Environment variables:
- `DB_URL` (optional, has default)
- `DB_USERNAME`
- `DB_PASSWORD`
- `INVENTORY_API_BASE_URL` (optional, default `https://ordernest-inventory-service.onrender.com`)

## API
Gateway base URL: `https://ordernest-api-gateway.onrender.com`
- `POST /api/orders`
- `GET /api/orders/{orderId}`
- `GET /api/orders/me`
- `POST /api/orders/{orderId}/cancel`
- `GET /api/orders/{orderId}/events`
- `POST /api/shipments/status` (admin)

Create order body:
```json
{
  "item": {
    "productId": "d641ef4b-d996-4580-8642-9666349e5f6d",
    "quantity": 4
  }
}
```

Response (`201`):
```json
{
  "orderId": "74f75b15-9d9f-4a68-a0d8-8f1f0dacc939"
}
```

## Swagger
- Local Swagger UI: `http://localhost:8082/swagger-ui/index.html`
- Local OpenAPI JSON: `http://localhost:8082/v3/api-docs`
- Live Swagger UI: `https://ordernest-order-service.onrender.com/swagger-ui/index.html`
- Live OpenAPI JSON: `https://ordernest-order-service.onrender.com/v3/api-docs`
- Use API Gateway URL for client calls: `https://ordernest-api-gateway.onrender.com`

## Health
- `http://localhost:8082/actuator/health`
- `https://ordernest-order-service.onrender.com/actuator/health`

## Postman
- `postman/ordernest-order-service.postman_collection.json`

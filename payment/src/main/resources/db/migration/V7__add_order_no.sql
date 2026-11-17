-- 외부 노출용 주문번호(UUID 문자열). PG(orderId)와 API에서 순차 PK 대신 쓴다.
-- 이미 있는 주문은 UUID()로 채운다. (v1이라 UUIDv7은 아니지만, 유일하고 추측하기 어려워 식별자로는 충분하다)
ALTER TABLE orders ADD COLUMN order_no VARCHAR(36) NULL;
UPDATE orders SET order_no = UUID() WHERE order_no IS NULL;
ALTER TABLE orders MODIFY COLUMN order_no VARCHAR(36) NOT NULL;
ALTER TABLE orders ADD CONSTRAINT uk_orders_order_no UNIQUE (order_no);

-- 결제 취소 진행 중(CANCELING) 상태 추가: PG 취소 호출 전에 주문을 선점해 중복 취소 호출을 막는다.
ALTER TABLE orders DROP CHECK ck_orders_status;
ALTER TABLE orders
    ADD CONSTRAINT ck_orders_status CHECK (status IN ('PENDING', 'PAYING', 'PAID', 'CANCELING', 'CANCELED'));

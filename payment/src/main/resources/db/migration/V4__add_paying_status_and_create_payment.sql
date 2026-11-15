-- 결제 진행 중(PAYING) 상태 추가: PG 호출 전에 주문을 선점해 중복 승인 호출을 막는다.
ALTER TABLE orders DROP CHECK ck_orders_status;
ALTER TABLE orders
    ADD CONSTRAINT ck_orders_status CHECK (status IN ('PENDING', 'PAYING', 'PAID', 'CANCELED'));

-- 결제 기록. Order와 1:1이므로 order_id에 UNIQUE를 걸어 이중 결제 기록을 DB가 마지막으로 막는다.
CREATE TABLE payment (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    order_id          BIGINT       NOT NULL,
    pg_transaction_id VARCHAR(100) NOT NULL,
    amount            BIGINT       NOT NULL,
    created_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_payment_order_id UNIQUE (order_id),
    CONSTRAINT uk_payment_pg_transaction_id UNIQUE (pg_transaction_id),
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_payment_amount CHECK (amount > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

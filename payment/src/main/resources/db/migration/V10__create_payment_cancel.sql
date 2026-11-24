-- phase-04 4-3: 사용자 결제 취소의 시도 기록. PG 취소를 호출하기 전에 REQUESTED로 만들고 결과를 상태로 남긴다.
-- Order와 1:N이다. PG가 취소를 거절하면 주문이 PAID로 돌아가 새 키로 다시 취소할 수 있기 때문이다.
-- 진행 중인 시도가 하나뿐인 것은 주문 상태 선점(PAID → CANCELING)이 보장한다. (MySQL에는 부분 유니크 인덱스가 없다)
-- 대사(4-4)용 인덱스는 4-4에서 실행 계획을 보고 정한다.
CREATE TABLE payment_cancel (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    order_id        BIGINT       NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    reason          VARCHAR(200) NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    failure_code    VARCHAR(50)  NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_payment_cancel_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_payment_cancel_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_payment_cancel_status CHECK (status IN ('REQUESTED', 'UNKNOWN', 'COMPLETED', 'FAILED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

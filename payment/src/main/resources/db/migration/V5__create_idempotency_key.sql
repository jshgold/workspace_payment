-- 결제 승인 요청의 멱등키. 같은 키의 재전송에는 첫 결과를 돌려주기 위해 키와 처리 상태를 DB에 기록한다.
-- 돈과 상태의 진실은 DB 한 곳에 둔다. (Redis와의 이중 쓰기를 피하기 위해)
CREATE TABLE idempotency_key (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    idempotency_key VARCHAR(100) NOT NULL,
    order_id        BIGINT       NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    failure_code    VARCHAR(50)  NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_idempotency_key_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_idempotency_key_status CHECK (status IN ('IN_PROGRESS', 'SUCCEEDED', 'FAILED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_idempotency_key_order_id ON idempotency_key (order_id);

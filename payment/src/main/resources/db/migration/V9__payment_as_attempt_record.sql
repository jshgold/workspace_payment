-- phase-04 4-2+: Payment를 "승인 결과"에서 "결제 시도 기록"으로 바꾼다.
-- PG 호출 전에 REQUESTED로 생성하고, 결과(승인/실패/결과 모름)를 상태로 남긴다. 멱등키도 이 행에 둔다.

-- 1. 주문 상태 확장 (결과 모름, 실패 종료, 만료, 취소 결과 모름)
ALTER TABLE orders DROP CHECK ck_orders_status;
ALTER TABLE orders
    ADD CONSTRAINT ck_orders_status CHECK (status IN (
        'PENDING', 'PAYING', 'PAYMENT_UNKNOWN', 'PAID', 'FAILED', 'EXPIRED',
        'CANCELING', 'CANCEL_UNKNOWN', 'CANCELED'));

-- 2. pg_transaction_id에는 이미 토스 paymentKey가 들어 있다. 이제 PG 호출 전에 요청의 paymentKey로 채우므로 이름을 맞춘다.
ALTER TABLE payment RENAME COLUMN pg_transaction_id TO payment_key;
ALTER TABLE payment RENAME INDEX uk_payment_pg_transaction_id TO uk_payment_payment_key;

-- 3. 시도 기록용 컬럼. 기존 행을 채운 뒤 NOT NULL로 바꾼다.
ALTER TABLE payment
    ADD COLUMN status          VARCHAR(20)  NULL,
    ADD COLUMN idempotency_key VARCHAR(100) NULL,
    ADD COLUMN failure_code    VARCHAR(50)  NULL,
    ADD COLUMN approved_amount BIGINT       NULL,
    ADD COLUMN updated_at      DATETIME(6)  NULL;

-- 기존 행은 모두 승인이 끝난 결제다. (재설계 전에는 승인 성공 시에만 행을 만들었다)
UPDATE payment
   SET status          = 'APPROVED',
       approved_amount = amount,
       updated_at      = created_at;

-- 성공한 멱등키만 붙일 결제 행이 있다. 실패·진행 중 키는 결제 행이 없어 옮기지 않는다.
UPDATE payment p
  JOIN idempotency_key k ON k.order_id = p.order_id AND k.status = 'SUCCEEDED'
   SET p.idempotency_key = k.idempotency_key;

-- 키 없이 저장된 결제(키 도입 전 데이터 등)는 다른 키와 겹치지 않는 값으로 채운다.
UPDATE payment
   SET idempotency_key = CONCAT('legacy-', id)
 WHERE idempotency_key IS NULL;

ALTER TABLE payment
    MODIFY COLUMN status          VARCHAR(20)  NOT NULL,
    MODIFY COLUMN idempotency_key VARCHAR(100) NOT NULL,
    MODIFY COLUMN updated_at      DATETIME(6)  NOT NULL,
    ADD CONSTRAINT uk_payment_idempotency_key UNIQUE (idempotency_key),
    ADD CONSTRAINT ck_payment_status CHECK (status IN ('REQUESTED', 'UNKNOWN', 'APPROVED', 'FAILED', 'CANCELED')),
    ADD CONSTRAINT ck_payment_approved_amount CHECK (approved_amount IS NULL OR approved_amount > 0);

-- 4. 멱등키 테이블은 payment.idempotency_key로 옮겼으므로 삭제한다. (사용자 승인 2026-11-20)
DROP TABLE idempotency_key;

-- 토스 paymentKey는 최대 200자이고, 식별자 길이는 버전 릴리즈 없이 바뀔 수 있다. (공식 문서: 식별자는 불투명한 문자열로 다룬다)
-- 기존 100자 컬럼은 긴 키가 오면 승인 후 DB 반영이 실패해 보상 취소까지 가게 되므로 200자로 넓힌다.
-- UNIQUE 인덱스(uk_payment_pg_transaction_id)는 utf8mb4 기준 800바이트로 InnoDB 제한(3072)에 여유가 있다.
ALTER TABLE payment MODIFY COLUMN pg_transaction_id VARCHAR(200) NOT NULL;

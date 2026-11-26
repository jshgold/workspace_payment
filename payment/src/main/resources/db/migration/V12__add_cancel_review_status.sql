-- phase-04 4-4: 취소 확인 필요 상태 추가. 취소 대사 조회가 NOT_FOUND_PAYMENT면 대사 대상에서 빼고 사람이 확인한다. (intent.md 4절, 5절 전이표)
-- CHECK 제약은 수정할 수 없어 지우고 다시 만든다. 데이터는 바뀌지 않는다. (사용자 승인 2026-11-26)
ALTER TABLE orders DROP CHECK ck_orders_status;
ALTER TABLE orders
    ADD CONSTRAINT ck_orders_status CHECK (status IN (
        'PENDING', 'PAYING', 'PAYMENT_UNKNOWN', 'PAID', 'FAILED', 'EXPIRED',
        'CANCELING', 'CANCEL_UNKNOWN', 'CANCEL_REVIEW', 'CANCELED'));

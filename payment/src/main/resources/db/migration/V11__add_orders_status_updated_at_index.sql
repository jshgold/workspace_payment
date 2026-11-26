-- phase-04 4-4: 대사·만료 대상 조회용 인덱스. (intent.md 8절 "대사·만료 대상 조회 인덱스")
-- 대상 쿼리: WHERE status IN (...) AND updated_at < ? AND id > ? ORDER BY id LIMIT ?
-- (status)만으로는 최근 PENDING이 많을 때 만료 쿼리가 PENDING 전체를 읽는다. 측정: docs/evidence/index-explain-reconciliation.md
CREATE INDEX idx_orders_status_updated_at ON orders (status, updated_at);

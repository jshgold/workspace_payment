# 대사·만료 대상 조회 실행 계획 비교

- 측정 시각 기준(NOW): 2026-11-25T20:06:37
- 데이터: 주문 199,997건 = 끝난 주문(PAID 70%, FAILED·EXPIRED·CANCELED 각 10%, 최근 90일) 196,959 + PENDING 3000(30분 지남 1000) + PAYING 20(5분 지남 5) + PAYMENT_UNKNOWN 7(1분 지남 5) + CANCELING 7(5분 지남 2) + CANCEL_UNKNOWN 4(1분 지남 3)

## 1. 인덱스 없음

| 쿼리 | type | key | rows (추정) | filtered | Extra | 실제 대상 건수 |
|---|---|---|---|---|---|---|
| 만료 (PENDING 30분) | ALL | null | 198477 | 3.33 | Using where | 500 |
| 승인 대사 (PAYMENT_UNKNOWN 1분) | ALL | null | 198477 | 3.33 | Using where | 5 |
| 승인 대사 (PAYING 5분) | ALL | null | 198477 | 3.33 | Using where | 5 |
| 취소 대사 (CANCEL_UNKNOWN 1분) | ALL | null | 198477 | 3.33 | Using where | 3 |
| 취소 대사 (CANCELING 5분) | ALL | null | 198477 | 3.33 | Using where | 2 |

<details><summary>EXPLAIN ANALYZE</summary>

**만료 (PENDING 30분)**
```
-> Limit: 500 row(s)  (cost=20225 rows=500) (actual time=24.1..24.2 rows=500 loops=1)
    -> Filter: ((orders.`status` = 'PENDING') and (orders.updated_at < TIMESTAMP'2026-11-25 19:36:37'))  (cost=20225 rows=6615) (actual time=24.1..24.2 rows=500 loops=1)
        -> Table scan on orders  (cost=20225 rows=198477) (actual time=0.0663..17.8 rows=197459 loops=1)
```

**승인 대사 (PAYMENT_UNKNOWN 1분)**
```
-> Limit: 500 row(s)  (cost=20225 rows=500) (actual time=25.9..26.1 rows=5 loops=1)
    -> Filter: ((orders.`status` = 'PAYMENT_UNKNOWN') and (orders.updated_at < TIMESTAMP'2026-11-25 20:05:37'))  (cost=20225 rows=6615) (actual time=25.9..26.1 rows=5 loops=1)
        -> Table scan on orders  (cost=20225 rows=198477) (actual time=0.0693..18.9 rows=199997 loops=1)
```

**승인 대사 (PAYING 5분)**
```
-> Limit: 500 row(s)  (cost=20225 rows=500) (actual time=25.9..26.1 rows=5 loops=1)
    -> Filter: ((orders.`status` = 'PAYING') and (orders.updated_at < TIMESTAMP'2026-11-25 20:01:37'))  (cost=20225 rows=6615) (actual time=25.9..26.1 rows=5 loops=1)
        -> Table scan on orders  (cost=20225 rows=198477) (actual time=0.0685..18.8 rows=199997 loops=1)
```

**취소 대사 (CANCEL_UNKNOWN 1분)**
```
-> Limit: 500 row(s)  (cost=20225 rows=500) (actual time=24.3..24.5 rows=3 loops=1)
    -> Filter: ((orders.`status` = 'CANCEL_UNKNOWN') and (orders.updated_at < TIMESTAMP'2026-11-25 20:05:37'))  (cost=20225 rows=6615) (actual time=24.3..24.5 rows=3 loops=1)
        -> Table scan on orders  (cost=20225 rows=198477) (actual time=0.0847..18.1 rows=199997 loops=1)
```

**취소 대사 (CANCELING 5분)**
```
-> Limit: 500 row(s)  (cost=20225 rows=500) (actual time=23.5..23.8 rows=2 loops=1)
    -> Filter: ((orders.`status` = 'CANCELING') and (orders.updated_at < TIMESTAMP'2026-11-25 20:01:37'))  (cost=20225 rows=6615) (actual time=23.5..23.8 rows=2 loops=1)
        -> Table scan on orders  (cost=20225 rows=198477) (actual time=0.0584..17.5 rows=199997 loops=1)
```

</details>

## 2. (status)

| 쿼리 | type | key | rows (추정) | filtered | Extra | 실제 대상 건수 |
|---|---|---|---|---|---|---|
| 만료 (PENDING 30분) | ref | idx_orders_status | 24800 | 33.33 | Using where | 500 |
| 승인 대사 (PAYMENT_UNKNOWN 1분) | ref | idx_orders_status | 24800 | 33.33 | Using where | 5 |
| 승인 대사 (PAYING 5분) | ref | idx_orders_status | 24800 | 33.33 | Using where | 5 |
| 취소 대사 (CANCEL_UNKNOWN 1분) | ref | idx_orders_status | 24800 | 33.33 | Using where | 3 |
| 취소 대사 (CANCELING 5분) | ref | idx_orders_status | 24800 | 33.33 | Using where | 2 |

<details><summary>EXPLAIN ANALYZE</summary>

**만료 (PENDING 30분)**
```
-> Limit: 500 row(s)  (cost=1958 rows=500) (actual time=0.287..0.34 rows=500 loops=1)
    -> Filter: (orders.updated_at < TIMESTAMP'2026-11-25 19:36:37')  (cost=1958 rows=8266) (actual time=0.287..0.324 rows=500 loops=1)
        -> Index lookup on orders using idx_orders_status (status='PENDING')  (cost=1958 rows=24800) (actual time=0.286..0.304 rows=500 loops=1)
```

**승인 대사 (PAYMENT_UNKNOWN 1분)**
```
-> Limit: 500 row(s)  (cost=1958 rows=500) (actual time=0.0113..0.0128 rows=5 loops=1)
    -> Filter: (orders.updated_at < TIMESTAMP'2026-11-25 20:05:37')  (cost=1958 rows=8266) (actual time=0.0111..0.0124 rows=5 loops=1)
        -> Index lookup on orders using idx_orders_status (status='PAYMENT_UNKNOWN')  (cost=1958 rows=24800) (actual time=0.0106..0.0115 rows=7 loops=1)
```

**승인 대사 (PAYING 5분)**
```
-> Limit: 500 row(s)  (cost=1958 rows=500) (actual time=0.0173..0.0199 rows=5 loops=1)
    -> Filter: (orders.updated_at < TIMESTAMP'2026-11-25 20:01:37')  (cost=1958 rows=8266) (actual time=0.0171..0.0195 rows=5 loops=1)
        -> Index lookup on orders using idx_orders_status (status='PAYING')  (cost=1958 rows=24800) (actual time=0.0164..0.018 rows=20 loops=1)
```

**취소 대사 (CANCEL_UNKNOWN 1분)**
```
-> Limit: 500 row(s)  (cost=1958 rows=500) (actual time=0.00862..0.00958 rows=3 loops=1)
    -> Filter: (orders.updated_at < TIMESTAMP'2026-11-25 20:05:37')  (cost=1958 rows=8266) (actual time=0.00846..0.00929 rows=3 loops=1)
        -> Index lookup on orders using idx_orders_status (status='CANCEL_UNKNOWN')  (cost=1958 rows=24800) (actual time=0.008..0.00862 rows=4 loops=1)
```

**취소 대사 (CANCELING 5분)**
```
-> Limit: 500 row(s)  (cost=1958 rows=500) (actual time=0.00912..0.0101 rows=2 loops=1)
    -> Filter: (orders.updated_at < TIMESTAMP'2026-11-25 20:01:37')  (cost=1958 rows=8266) (actual time=0.00896..0.00988 rows=2 loops=1)
        -> Index lookup on orders using idx_orders_status (status='CANCELING')  (cost=1958 rows=24800) (actual time=0.00862..0.00921 rows=7 loops=1)
```

</details>

## 3. (status, updated_at)

| 쿼리 | type | key | rows (추정) | filtered | Extra | 실제 대상 건수 |
|---|---|---|---|---|---|---|
| 만료 (PENDING 30분) | range | idx_orders_status_updated_at | 1 | 100.0 | Using where; Using index | 500 |
| 승인 대사 (PAYMENT_UNKNOWN 1분) | range | idx_orders_status_updated_at | 1 | 100.0 | Using where; Using index | 5 |
| 승인 대사 (PAYING 5분) | range | idx_orders_status_updated_at | 1 | 100.0 | Using where; Using index | 5 |
| 취소 대사 (CANCEL_UNKNOWN 1분) | range | idx_orders_status_updated_at | 1 | 100.0 | Using where; Using index | 3 |
| 취소 대사 (CANCELING 5분) | range | idx_orders_status_updated_at | 1 | 100.0 | Using where; Using index | 2 |

<details><summary>EXPLAIN ANALYZE</summary>

**만료 (PENDING 30분)**
```
-> Limit: 500 row(s)  (cost=1.21 rows=1) (actual time=0.0108..0.136 rows=500 loops=1)
    -> Filter: ((orders.`status` = 'PENDING') and (orders.updated_at < TIMESTAMP'2026-11-25 19:36:37'))  (cost=1.21 rows=1) (actual time=0.0104..0.12 rows=500 loops=1)
        -> Covering index range scan on orders using idx_orders_status_updated_at over (status = 'PENDING' AND updated_at < '2026-11-25 19:36:37.000000')  (cost=1.21 rows=1) (actual time=0.00908..0.0774 rows=500 loops=1)
```

**승인 대사 (PAYMENT_UNKNOWN 1분)**
```
-> Limit: 500 row(s)  (cost=1.21 rows=1) (actual time=0.00542..0.00834 rows=5 loops=1)
    -> Filter: ((orders.`status` = 'PAYMENT_UNKNOWN') and (orders.updated_at < TIMESTAMP'2026-11-25 20:05:37'))  (cost=1.21 rows=1) (actual time=0.00517..0.00792 rows=5 loops=1)
        -> Covering index range scan on orders using idx_orders_status_updated_at over (status = 'PAYMENT_UNKNOWN' AND updated_at < '2026-11-25 20:05:37.000000')  (cost=1.21 rows=1) (actual time=0.00458..0.00675 rows=5 loops=1)
```

**승인 대사 (PAYING 5분)**
```
-> Limit: 500 row(s)  (cost=1.21 rows=1) (actual time=0.00487..0.00737 rows=5 loops=1)
    -> Filter: ((orders.`status` = 'PAYING') and (orders.updated_at < TIMESTAMP'2026-11-25 20:01:37'))  (cost=1.21 rows=1) (actual time=0.00471..0.007 rows=5 loops=1)
        -> Covering index range scan on orders using idx_orders_status_updated_at over (status = 'PAYING' AND updated_at < '2026-11-25 20:01:37.000000')  (cost=1.21 rows=1) (actual time=0.00433..0.00621 rows=5 loops=1)
```

**취소 대사 (CANCEL_UNKNOWN 1분)**
```
-> Limit: 500 row(s)  (cost=1.21 rows=1) (actual time=0.00792..0.01 rows=3 loops=1)
    -> Filter: ((orders.`status` = 'CANCEL_UNKNOWN') and (orders.updated_at < TIMESTAMP'2026-11-25 20:05:37'))  (cost=1.21 rows=1) (actual time=0.00767..0.00971 rows=3 loops=1)
        -> Covering index range scan on orders using idx_orders_status_updated_at over (status = 'CANCEL_UNKNOWN' AND updated_at < '2026-11-25 20:05:37.000000')  (cost=1.21 rows=1) (actual time=0.00692..0.00863 rows=3 loops=1)
```

**취소 대사 (CANCELING 5분)**
```
-> Limit: 500 row(s)  (cost=1.21 rows=1) (actual time=0.00358..0.00454 rows=2 loops=1)
    -> Filter: ((orders.`status` = 'CANCELING') and (orders.updated_at < TIMESTAMP'2026-11-25 20:01:37'))  (cost=1.21 rows=1) (actual time=0.00337..0.00429 rows=2 loops=1)
        -> Covering index range scan on orders using idx_orders_status_updated_at over (status = 'CANCELING' AND updated_at < '2026-11-25 20:01:37.000000')  (cost=1.21 rows=1) (actual time=0.003..0.00371 rows=2 loops=1)
```

</details>

## 4. 둘 다 있을 때 옵티마이저 선택 (힌트 없음)

| 쿼리 | type | key | rows (추정) | filtered | Extra | 실제 대상 건수 |
|---|---|---|---|---|---|---|
| 만료 (PENDING 30분) | range | idx_orders_status_updated_at | 1000 | 100.0 | Using where; Using index | 500 |
| 승인 대사 (PAYMENT_UNKNOWN 1분) | range | idx_orders_status_updated_at | 5 | 100.0 | Using where; Using index | 5 |
| 승인 대사 (PAYING 5분) | range | idx_orders_status_updated_at | 5 | 100.0 | Using where; Using index | 5 |
| 취소 대사 (CANCEL_UNKNOWN 1분) | ref | idx_orders_status | 4 | 33.33 | Using where | 3 |
| 취소 대사 (CANCELING 5분) | range | idx_orders_status_updated_at | 2 | 100.0 | Using where; Using index | 2 |

<details><summary>EXPLAIN ANALYZE</summary>

**만료 (PENDING 30분)**
```
-> Limit: 500 row(s)  (cost=213 rows=500) (actual time=0.0122..0.123 rows=500 loops=1)
    -> Filter: ((orders.`status` = 'PENDING') and (orders.updated_at < TIMESTAMP'2026-11-25 19:36:37'))  (cost=213 rows=1000) (actual time=0.012..0.107 rows=500 loops=1)
        -> Covering index range scan on orders using idx_orders_status_updated_at over (status = 'PENDING' AND updated_at < '2026-11-25 19:36:37.000000')  (cost=213 rows=1000) (actual time=0.0117..0.0664 rows=500 loops=1)
```

**승인 대사 (PAYMENT_UNKNOWN 1분)**
```
-> Limit: 500 row(s)  (cost=2.06 rows=5) (actual time=0.00475..0.00596 rows=5 loops=1)
    -> Filter: ((orders.`status` = 'PAYMENT_UNKNOWN') and (orders.updated_at < TIMESTAMP'2026-11-25 20:05:37'))  (cost=2.06 rows=5) (actual time=0.00462..0.00571 rows=5 loops=1)
        -> Covering index range scan on orders using idx_orders_status_updated_at over (status = 'PAYMENT_UNKNOWN' AND updated_at < '2026-11-25 20:05:37.000000')  (cost=2.06 rows=5) (actual time=0.00413..0.00471 rows=5 loops=1)
```

**승인 대사 (PAYING 5분)**
```
-> Limit: 500 row(s)  (cost=2.06 rows=5) (actual time=0.00367..0.00467 rows=5 loops=1)
    -> Filter: ((orders.`status` = 'PAYING') and (orders.updated_at < TIMESTAMP'2026-11-25 20:01:37'))  (cost=2.06 rows=5) (actual time=0.00354..0.00425 rows=5 loops=1)
        -> Covering index range scan on orders using idx_orders_status_updated_at over (status = 'PAYING' AND updated_at < '2026-11-25 20:01:37.000000')  (cost=2.06 rows=5) (actual time=0.00321..0.00367 rows=5 loops=1)
```

**취소 대사 (CANCEL_UNKNOWN 1분)**
```
-> Limit: 500 row(s)  (cost=1.13 rows=1.33) (actual time=0.00696..0.00817 rows=3 loops=1)
    -> Filter: (orders.updated_at < TIMESTAMP'2026-11-25 20:05:37')  (cost=1.13 rows=1.33) (actual time=0.00675..0.00783 rows=3 loops=1)
        -> Index lookup on orders using idx_orders_status (status='CANCEL_UNKNOWN')  (cost=1.13 rows=4) (actual time=0.00617..0.00688 rows=4 loops=1)
```

**취소 대사 (CANCELING 5분)**
```
-> Limit: 500 row(s)  (cost=1.42 rows=2) (actual time=0.00375..0.00425 rows=2 loops=1)
    -> Filter: ((orders.`status` = 'CANCELING') and (orders.updated_at < TIMESTAMP'2026-11-25 20:01:37'))  (cost=1.42 rows=2) (actual time=0.00362..0.00408 rows=2 loops=1)
        -> Covering index range scan on orders using idx_orders_status_updated_at over (status = 'CANCELING' AND updated_at < '2026-11-25 20:01:37.000000')  (cost=1.42 rows=2) (actual time=0.00321..0.00354 rows=2 loops=1)
```

</details>

---

# 시나리오 B: 평소 상태 (만료 쿼리만)

- 데이터: 시나리오 A에서 30분 지난 PENDING 1000건 중 970건을 EXPIRED로 바꾸고(만료가 처리한 것처럼), 최근 PENDING 1000건을 더 넣음 → PENDING 3030건 (30분 지남 30)

## B-1. 인덱스 없음

| 쿼리 | type | key | rows (추정) | filtered | Extra | 실제 대상 건수 |
|---|---|---|---|---|---|---|
| 만료 (PENDING 30분) | ALL | null | 199766 | 4.76 | Using where | 30 |

<details><summary>EXPLAIN ANALYZE</summary>

**만료 (PENDING 30분)**
```
-> Limit: 500 row(s)  (cost=20354 rows=500) (actual time=23.7..24.2 rows=30 loops=1)
    -> Filter: ((orders.`status` = 'PENDING') and (orders.updated_at < TIMESTAMP'2026-11-25 19:36:37'))  (cost=20354 rows=9512) (actual time=23.7..24.2 rows=30 loops=1)
        -> Table scan on orders  (cost=20354 rows=199766) (actual time=0.0642..17.7 rows=200997 loops=1)
```

</details>

## B-2. (status)

| 쿼리 | type | key | rows (추정) | filtered | Extra | 실제 대상 건수 |
|---|---|---|---|---|---|---|
| 만료 (PENDING 30분) | ref | idx_orders_status | 28538 | 33.33 | Using where | 30 |

<details><summary>EXPLAIN ANALYZE</summary>

**만료 (PENDING 30분)**
```
-> Limit: 500 row(s)  (cost=2082 rows=500) (actual time=0.311..1.04 rows=30 loops=1)
    -> Filter: (orders.updated_at < TIMESTAMP'2026-11-25 19:36:37')  (cost=2082 rows=9512) (actual time=0.311..1.04 rows=30 loops=1)
        -> Index lookup on orders using idx_orders_status (status='PENDING')  (cost=2082 rows=28538) (actual time=0.31..0.965 rows=3030 loops=1)
```

</details>

## B-3. (status, updated_at)

| 쿼리 | type | key | rows (추정) | filtered | Extra | 실제 대상 건수 |
|---|---|---|---|---|---|---|
| 만료 (PENDING 30분) | range | idx_orders_status_updated_at | 1 | 100.0 | Using where; Using index | 30 |

<details><summary>EXPLAIN ANALYZE</summary>

**만료 (PENDING 30분)**
```
-> Limit: 500 row(s)  (cost=1.21 rows=1) (actual time=0.00629..0.0148 rows=30 loops=1)
    -> Filter: ((orders.`status` = 'PENDING') and (orders.updated_at < TIMESTAMP'2026-11-25 19:36:37'))  (cost=1.21 rows=1) (actual time=0.00604..0.0135 rows=30 loops=1)
        -> Covering index range scan on orders using idx_orders_status_updated_at over (status = 'PENDING' AND updated_at < '2026-11-25 19:36:37.000000')  (cost=1.21 rows=1) (actual time=0.00529..0.0107 rows=30 loops=1)
```

</details>

## B-4. 옵티마이저 선택 (힌트 없음)

| 쿼리 | type | key | rows (추정) | filtered | Extra | 실제 대상 건수 |
|---|---|---|---|---|---|---|
| 만료 (PENDING 30분) | range | idx_orders_status_updated_at | 30 | 100.0 | Using where; Using index | 30 |

<details><summary>EXPLAIN ANALYZE</summary>

**만료 (PENDING 30분)**
```
-> Limit: 500 row(s)  (cost=7.36 rows=30) (actual time=0.00625..0.0109 rows=30 loops=1)
    -> Filter: ((orders.`status` = 'PENDING') and (orders.updated_at < TIMESTAMP'2026-11-25 19:36:37'))  (cost=7.36 rows=30) (actual time=0.00621..0.00987 rows=30 loops=1)
        -> Covering index range scan on orders using idx_orders_status_updated_at over (status = 'PENDING' AND updated_at < '2026-11-25 19:36:37.000000')  (cost=7.36 rows=30) (actual time=0.00575..0.007 rows=30 loops=1)
```

</details>


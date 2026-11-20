# 결제 트러블슈팅 테스트 목록

어떤 테스트를 어느 버전에서 돌렸는지만 정리한다. 결과 수치는 "기록 위치" 문서에 있다.

- **개선 X**: 재고를 PG 승인 뒤(`complete`)에서 차감, 멱등키는 별도 테이블 (`73ce46c`)
- **A**: 재고 차감을 PG 앞(`startPaying`)으로 (`7c545d4`, merge `5e7823f`)
- **B**: 멱등키를 `payment` 컬럼으로, 트랜잭션 합치기 (phase-04 4-2+, `ac773a8`)

공통 조건: 가짜 PG, local 프로필, SQL 로그 끔, 모두 단발(1회) 측정. (예외는 비고에 적음)

| # | 테스트 | 조건 | 개선 X | A | B | 기록 위치 |
|---|---|---|---|---|---|---|
| 1 | JUnit 경합 | 재고 1, 주문 10, PG 지연 0, 비관적 락·원자적 쿼리 | ✓ | ✓ | | stock-deduction-timing 7 |
| 2 | k6 경합 | 재고 1, 주문 10, PG 지연 0 | ✓ | | | stock-deduction-timing 7 |
| 3 | k6 경합 | 재고 1, 주문 100, PG 지연 300ms | ✓ | ✓ | ✓ | stock-deduction-timing 7 / granularity 2-9 |
| 4 | 단건 SQL·트랜잭션 수 (Hibernate 통계) | 재고 1, A → B 순서, PG 지연 0 | ✓ | | | granularity 2-1 |
| 5 | 단건 구간별 시간 | PG 지연 300ms | ✓ (3회 평균) | ✓ (1회) | ✓ (3회 평균) | granularity 2-1-1 / 2-8 / 2-9 |
| 6 | k6 해피, 결제만 | 재고 200, 주문 200 | ✓ | ✓ | ✓ | granularity 2-4 / 2-8 / 2-9 |
| 7 | 6번 + 커넥션 대기·COMMIT 분해 | 재고 200, 주문 200 | ✓ | ✓ | ✓ | granularity 2-7 / 2-8 / 2-9 |
| 8 | k6 해피, 결제만 | 재고 1000, 주문 1000 | ✓ | ✓ | ✓ | granularity 2-5 / 2-8 / 2-9 |
| 9 | 스레드 고갈 (결제 + 상품 조회) | 스레드 20, 경합 100 | ✓ | ✓ | | stock-deduction-timing 7 |
| 10 | 스레드 고갈 | 스레드 200, 경합 500 | ✓ | ✓ | | stock-deduction-timing 7 |
| 11 | 스레드 고갈 | 스레드 200, 경합 1000 | ✓ | ✓ | | stock-deduction-timing 7 |
| 12 | 스레드 고갈 | 스레드 200, 해피 1000 | ✓ | ✓ | | stock-deduction-timing 7 |

- 7번은 개선 X에서는 6번과 다른 실행이다. (2-4는 분해 계측 추가 전, 2-7은 추가 후) A·B에서는 6·7번이 같은 실행이다.
- 문서 이름 줄임: stock-deduction-timing = `payment-stock-deduction-timing.md`, granularity = `payment-transaction-granularity.md`

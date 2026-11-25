# 대사·만료 구현 전 재현 테스트 결과 (phase-04 4-4)

> 실행: 2026-11-25. 커밋 `0c64340` 위에 재현 테스트와 컴파일용 뼈대만 추가한 상태.
> 테스트는 **구현 후의 올바른 동작**을 기대하도록 작성했다. 지금은 실패하는 것이 정상이고, 구현 후 같은 테스트가 통과해야 한다.
> 계획과 경위: `docs/payment-reconciliation-discussion.md` R절.

## 실행 방법

```
./gradlew test --tests "*PaymentReconciliationTest" --tests "*OrderExpirationTest" --tests "*ReconciliationConcurrencyTest" --console=plain
```

- 모두 Testcontainers MySQL 8.0 + 가짜 PG(`FakePaymentClient`). 실제 PG는 호출하지 않는다.
- 조건: 상품 가격 10,000원, 재고 5, 주문 수량 2 (서버 금액 20,000원, 결제 시작 후 재고 3). X1만 재고 100, 주문 20개.
- 대상 상태는 실제 흐름(`PaymentFacade`, 상태 서비스)으로 만들고, 시간 경과만 `updated_at`(만료는 `created_at`도)을 JVM 시각 기준으로 과거로 옮겨 만든다.

## 추가한 뼈대 (컴파일용)

| 파일 | 내용 |
|---|---|
| `PaymentClient.lookup(paymentKey)` | `Optional<PgPaymentLookup>`. 빈 값 = `NOT_FOUND_PAYMENT`. 조회 실패는 기존 `PaymentTimeoutException`·`PaymentGatewayException` |
| `PgPaymentLookup`, `PgPaymentStatus` (신규) | 조회 결과(상태, 금액). 상태 이름은 토스와 같다 |
| `FakePaymentClient` | `willLookup(status, amount)`, `willLookupNotFound()`, `willTimeoutLookup()`, `lookupCallCount()`. 시나리오 미설정 조회는 `IllegalStateException` |
| `TossPaymentClient.lookup` | `UnsupportedOperationException` (구현 단계에서 연동) |
| `PaymentReconciliationFacade` (신규) | `reconcilePayments()`, `reconcileCancels()` — `UnsupportedOperationException` |
| `OrderExpirationService` (신규) | `expire()` — `UnsupportedOperationException` |

계획과 다른 점: `NOT_FOUND_PAYMENT`를 별도 예외가 아니라 `Optional` 빈 값으로 표현했다. 조회 API에서 "없음"은 장애가 아니라 정상 응답이고, 새 `ErrorCode`가 필요 없어서다.

뼈대 추가 후 기존 PG 클라이언트 테스트 회귀: `FakePaymentClientTest` 9, `PaymentClientSwitchTest` 4, `TossPaymentClientTest` 19, `TossPaymentClientCancelTest` 22 — 모두 통과.

## 결과: 16개 중 16개 실패

모든 테스트가 준비 단계(실제 흐름으로 대상 상태 만들기, R6~R8의 `PAYMENT_CANCEL_FAILED` 준비 확인 포함)를 통과한 뒤, 대사·만료 진입점에서 실패했다.

| 코드 | 시나리오 | 기대 (구현 후) | 실제 (구현 전) |
|---|---|---|---|
| R1 | `PAYMENT_UNKNOWN` 6분, 조회 `DONE` 20,000 | `PAID`/`APPROVED`, 재고 3, 승인 0 | `UnsupportedOperationException` |
| R2 | `PAYMENT_UNKNOWN` 6분, 조회 `ABORTED` | `FAILED`/`FAILED`, 재고 5 | 같음 |
| R3 | `PAYING` 6분 + 1분, 조회 `DONE` | 6분만 `PAID`, 1분은 `PAYING`, 조회 1번 | 같음 |
| R4 | `PAYMENT_UNKNOWN` 6분, `NOT_FOUND_PAYMENT` | `FAILED`/`FAILED`, 재고 5 | 같음 |
| R5 | `PAYMENT_UNKNOWN` 6분, 조회 타임아웃 | 변화 없음, 재고 3 | 같음 |
| R6 | 보상 취소 실패 건, 조회 `DONE` 15,000 | 재취소 1번, `FAILED` + `PAYMENT_AMOUNT_MISMATCH`, 재고 5 | 같음 |
| R7 | 보상 취소 실패 건, 조회 `CANCELED` | 재취소 0, `FAILED`, 재고 5 | 같음 |
| R8 | 보상 취소 실패 건(승인 금액 = 서버 금액), 조회 `DONE` 20,000 | 재취소 0, `PAID`/`APPROVED`, 재고 3 | 같음 |
| C1 | `CANCEL_UNKNOWN` 6분, 조회 `CANCELED` | `CANCELED`/`CANCELED`, 취소 시도 `COMPLETED`, 재고 5 | 같음 |
| C2 | `CANCEL_UNKNOWN` 6분, 조회 `DONE` | `PAID`/`APPROVED`, 취소 시도 `FAILED`, 재고 3 | 같음 |
| C3 | `CANCELING` 6분, 조회 `CANCELED` | C1과 같음 | 같음 |
| C4 | `CANCEL_UNKNOWN` 6분, `NOT_FOUND_PAYMENT` | 예외 없이 변화 없음 | 회차가 예외로 끝남 (`UnsupportedOperationException`) |
| E1 | `PENDING` 31분 + 29분 | 31분만 `EXPIRED`, 재고 5 | `UnsupportedOperationException` |
| E2 | 만료 후 승인 요청 | `ORDER_NOT_PAYABLE`, PG 승인 0, 결제 기록 없음 | 만료 단계에서 `UnsupportedOperationException` |
| X1 | 31분 `PENDING` 20개에 만료 + 승인 20건 동시 | 주문마다 `EXPIRED` 또는 `PAID` 하나, 재고·PG 횟수 일치, 만료 예외 없음 | 만료 스레드 예외 (승인 20건은 모두 `PAID`로 진행) |
| X2 | 같은 `PAYMENT_UNKNOWN`에 대사 2개 동시, 조회 `ABORTED` 300ms | `FAILED` 한 번, 재고 5, 두 대사 예외 없음 | 두 대사 모두 예외 |

**참고 (E2):** 만료된 주문의 승인 거절 자체는 이미 동작한다. `PaymentReplayer.rejectNotPending`이 `PENDING`이 아니고 진행·완료도 아닌 상태를 `ORDER_NOT_PAYABLE`로 거절한다. E2가 확인하는 것은 "만료 작업이 만든 `EXPIRED`"로 이 경로가 이어지는지다.

**참고 (X1):** 만료 쪽 예외 없음을 함께 검사하지 않으면, 만료가 아무것도 하지 않아도 20건이 모두 `PAID`가 되어 통과해 버린다. 그래서 "만료 작업은 예외 없이 끝나야 한다"를 검증에 넣었다.

## 원본 실패 메시지 (요지)

```
PaymentReconciliationTest (R1~R8, C1~C3)
    java.lang.UnsupportedOperationException: 승인 대사는 phase-04 4-4 구현 단계에서 작성한다.
    java.lang.UnsupportedOperationException: 취소 대사는 phase-04 4-4 구현 단계에서 작성한다.

PaymentReconciliationTest > C4
    org.opentest4j.AssertionFailedError: [건 하나의 장애로 회차가 예외로 끝나지 않는다]
    expected: null
     but was: java.lang.UnsupportedOperationException: 취소 대사는 phase-04 4-4 구현 단계에서 작성한다.

OrderExpirationTest (E1, E2)
    java.lang.UnsupportedOperationException: 주문 만료는 phase-04 4-4 구현 단계에서 작성한다.

ReconciliationConcurrencyTest > X1
    java.lang.AssertionError: [만료 작업은 예외 없이 끝나야 한다]
    Expecting empty but was: [java.lang.UnsupportedOperationException: 주문 만료는 phase-04 4-4 구현 단계에서 작성한다. ...]

ReconciliationConcurrencyTest > X2
    java.lang.AssertionError: [늦게 온 대사는 상태가 이미 바뀐 것을 보고 건너뛴다]
    Expecting empty but was: [UnsupportedOperationException: 승인 대사는 ..., UnsupportedOperationException: 승인 대사는 ...]

16 tests completed, 16 failed
```

## 구현 후 결과

(구현 후 같은 명령으로 다시 실행해 채운다)

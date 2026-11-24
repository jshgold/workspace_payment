# 취소 재설계 전 재현 테스트 결과 (phase-04 4-3)

> 실행: 2026-11-25, 커밋 `bd3873b` 위에 테스트와 가짜 PG 시나리오(`FakePaymentClient.willTimeoutCancel()`)만 추가한 상태.
> 테스트는 **재설계 후의 올바른 동작**을 기대하도록 작성했다. 지금은 실패하는 것이 정상이고, 재설계 후 같은 테스트가 통과해야 한다.

## 실행 방법

```
./gradlew test --tests "*PaymentCancelAttemptRecordTest" --tests "*TossPaymentClientCancelTest" --console=plain
```

- `PaymentCancelAttemptRecordTest`: Testcontainers MySQL 8.0 + 가짜 PG. 승인은 실제 `PaymentFacade`로 거쳐 `PAID` 주문을 만든다.
- `TossPaymentClientCancelTest`: `MockRestServiceServer`로 토스 응답을 흉내 낸다. 실제 PG는 호출하지 않는다.
- 조건: 상품 가격 10,000원, 재고 5, 주문 수량 2 (승인 후 재고 3)

## 결과: 4개 중 4개 실패

| 시나리오 | 기대 (재설계 후) | 실제 (재설계 전) |
|---|---|---|
| PG 취소 타임아웃 → 주문 상태 | `CANCEL_UNKNOWN`, 재고 3 유지 | **`CANCELING`에 고착** (빠져나갈 간선 없음) |
| PG 취소 거절 → 취소 시도 기록 | `payment_cancel` 1행, `FAILED` + `PAYMENT_CANCEL_DECLINED` | **기록할 테이블이 없음** (로그만 남음) |
| 취소 성공 후 같은 요청 재전송 | 같은 결과(`CANCELED`), PG 취소 1번 | **`IllegalStateException`** → API에서는 409 `STATE_CONFLICT`. 클라이언트는 취소가 실패한 줄 안다 |
| 토스 `400 ALREADY_CANCELED_PAYMENT` | 취소 성공 | **`UnsupportedOperationException`** (토스 취소 미구현) |

두 번째 시나리오는 검증 지점에 닿기 전에 `BadSqlGrammarException`(테이블 없음)으로 실패했다. 기록할 곳 자체가 없다는 것이 결함이므로 이 실패로 재현으로 본다.

## 원본 실패 메시지

```
PaymentCancelAttemptRecordTest

- PG 취소 응답이 타임아웃되면 주문은 CANCEL_UNKNOWN이 되고, 재고는 복구되지 않으며 결제는 APPROVED로 남는다
    org.opentest4j.AssertionFailedError: [취소 응답을 놓친 주문은 진행 중(CANCELING)이 아니라 결과 모름(CANCEL_UNKNOWN)이어야 한다]
    expected: CANCEL_UNKNOWN
     but was: CANCELING

- PG가 취소를 거절하면 취소 시도가 FAILED와 실패 코드로 남고, 주문은 PAID로 돌아간다
    org.springframework.jdbc.BadSqlGrammarException: PreparedStatementCallback;
    bad SQL grammar [SELECT status, failure_code FROM payment_cancel WHERE order_id = ?]

- 취소에 성공한 뒤 같은 요청이 재전송되면 같은 결과(CANCELED)를 받고 PG 취소는 다시 호출되지 않는다
    java.lang.IllegalStateException: 결제 취소를 시작할 수 없는 주문 상태입니다. 현재 상태: CANCELED

TossPaymentClientCancelTest

- 이미 취소된 결제(400 ALREADY_CANCELED_PAYMENT)는 전액 환불이 끝난 것이므로 실패가 아니라 취소 성공이다
    java.lang.AssertionError: Expecting code not to raise a throwable but caught
      "java.lang.UnsupportedOperationException: 토스 취소 연동은 phase-04 4-3에서 구현한다."
```

## 재설계 후 결과 (2026-11-25)

같은 명령으로 다시 실행했다. **4개 모두 통과.**
테스트 코드는 진입점 시그니처(`cancel(orderId, reason)` → `cancel(orderId, idempotencyKey, reason)`)와
정리 코드(`DELETE FROM payment_cancel`)만 바꿨고, 기대값은 바꾸지 않았다.
`TossPaymentClientCancelTest`의 재현 테스트는 `ALREADY_REFUND_PAYMENT`를 함께 보는 매개변수 테스트로 바꾸고,
"예외 없음"에 더해 `alreadyCanceled=true`까지 확인하도록 기대를 좁혔다.

| 시나리오 | 결과 |
|---|---|
| PG 취소 타임아웃 | 주문 `CANCEL_UNKNOWN`, 취소 시도 `UNKNOWN`, 결제 `APPROVED`, 재고 3 유지 |
| PG 취소 거절 | `payment_cancel` 1행 `FAILED` + `PAYMENT_CANCEL_DECLINED`, 주문 `PAID` |
| 취소 성공 후 같은 키 재전송 | 첫 응답과 같은 `PaymentResponse`, PG 취소 1번 |
| 토스 `ALREADY_CANCELED_PAYMENT` | `PgCancelResult(alreadyCanceled=true)` 반환 (`ALREADY_REFUND_PAYMENT`도 같음) |

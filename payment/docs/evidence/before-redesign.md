# 결제 모델 재설계 전 재현 테스트 결과 (phase-04 4-2+)

> 실행: 2026-11-20, tag `before-payment-redesign` (커밋 `07eca74`) 위에 테스트만 추가한 상태.
> 테스트는 **재설계 후의 올바른 동작**을 기대하도록 작성했다. 따라서 지금은 실패하는 것이 정상이고, 재설계 후 같은 테스트가 통과해야 한다.

## 실행 방법

```
./gradlew test --tests "*PaymentAttemptRecordTest" --console=plain
```

- 시나리오: `src/test/java/com/payment/payment/service/PaymentAttemptRecordTestSupport.java`
- 구현체별: `PessimisticLockPaymentAttemptRecordTest`, `AtomicUpdatePaymentAttemptRecordTest`
- 환경: Testcontainers MySQL 8.0, 가짜 PG(`FakePaymentClient`), 진입점은 실제 API와 같은 `PaymentApprovalFacade`(멱등키 포함)
- 조건: 상품 가격 10,000원, 재고 5, 주문 수량 2 (금액 20,000원)

## 결과: 8개 중 8개 실패 (두 구현체 모두 같은 결과)

| 시나리오 | 기대 (재설계 후) | 실제 (재설계 전) |
|---|---|---|
| PG 거절 → Payment 행 | `FAILED` + `failure_code`로 남음 | **행 없음** |
| PG 거절 → 새 키로 같은 주문 재결제 | 주문 `FAILED`, 재결제 거절, PG 호출 1번 | **재결제가 성공해 주문 `PAID`** |
| PG 타임아웃 → Payment 행 | `paymentKey`, 금액과 함께 남음 | **행 없음** |
| PG 타임아웃 → 주문 상태 | `PAYMENT_UNKNOWN` | **`PAYING`** |

모든 실패가 위 검증 지점에서 났다. (설정 오류나 다른 예외로 실패한 것이 아니다)

## 원본 실패 메시지

```
PessimisticLockPaymentAttemptRecordTest / AtomicUpdatePaymentAttemptRecordTest (동일)

- PG가 승인을 거절하면 Payment 행이 FAILED와 실패 코드로 남고, 재고는 원래대로 복구된다
    java.lang.AssertionError: [거절된 결제 시도도 Payment 행으로 남아야 한다]
    Expecting Optional to contain a value but it was empty.

- PG가 승인을 거절하면 주문은 FAILED로 끝나고, 새 키로 같은 주문을 다시 결제해도 거절되며 PG를 다시 부르지 않는다
    org.opentest4j.AssertionFailedError: [확정 실패한 주문은 FAILED로 종료해야 한다 (재시도는 새 주문)]
    expected: "FAILED" but was: "PAID"

- PG 응답이 타임아웃되면 Payment 행이 paymentKey와 금액을 담은 채 남고, 재고는 차감된 채로 유지된다
    java.lang.AssertionError: [결과를 모르는 결제 시도도 Payment 행으로 남아야 한다 (무엇을 시도했는지)]
    Expecting Optional to contain a value but it was empty.

- PG 응답이 타임아웃되면 주문은 PAYMENT_UNKNOWN이 되고, 같은 키의 재요청은 '처리 중'을 받으며 PG를 다시 부르지 않는다
    org.opentest4j.AssertionFailedError: [응답을 놓친 주문은 진행 중(PAYING)이 아니라 결과 모름(PAYMENT_UNKNOWN)이어야 한다]
    expected: "PAYMENT_UNKNOWN" but was: "PAYING"
```

## 확인한 것

- **거절된 결제는 흔적이 없다.** 주문이 `PENDING`으로 돌아가고 Payment 행도 없다. 무엇을 시도했다가 왜 거절됐는지는 멱등키 테이블의 `failure_code`에만 남는다.
- **거절 후 같은 주문을 새 키로 결제하면 그대로 성공한다.** 주문이 `PENDING`으로 돌아가 있으므로 막을 장치가 없다. 같은 주문번호(`orderNo`)가 PG에 두 번 전달된다.
- **타임아웃 건도 Payment 행이 없다.** 주문 `PAYING`과 멱등키 `IN_PROGRESS`만 남아, 대사할 때 어떤 `paymentKey`로 무엇을 조회해야 하는지 결제 쪽에 기록이 없다.
- **`PAYING`이 "진행 중"과 "결과 모름"을 겸한다.** 타임아웃 후에도 `PAYING`이라, 대사 대상을 상태가 아니라 경과 시간으로 추측해야 한다.

## 재설계 후 결과 (2026-11-20)

같은 명령으로 다시 실행했다. 테스트 코드는 진입점 연결(`PaymentApprovalFacade` 삭제 → `PaymentFacade`)과
`getPgTransactionId()` → `getPaymentKey()` 이름 변경만 했고, 기대값은 바꾸지 않았다.

| 테스트 클래스 | 결과 |
|---|---|
| `PessimisticLockPaymentAttemptRecordTest` | 4개 모두 통과 |
| `AtomicUpdatePaymentAttemptRecordTest` | 4개 모두 통과 |

이번에는 첫 단언에서 멈추지 않으므로 재고, PG 호출 횟수, 같은 키 재요청 응답까지 모두 평가됐다.

## 테스트 작성 메모

- 아직 없는 상태 값(`FAILED`, `PAYMENT_UNKNOWN`)과 컬럼(`payment.status`, `failure_code`)은 컴파일되도록 enum 이름 문자열과 JDBC 조회로 검증한다. 재설계 후 타입으로 바꿔도 된다.
- 첫 단언에서 멈췄으므로 그 뒤 단언(재고, PG 호출 횟수, 같은 키 재요청 응답)은 이번 실행에서 평가되지 않았다.
- `FAILED` 주문 재결제 시 돌려줄 에러 코드는 정하지 않았다. 테스트는 "거절된다"와 "PG를 다시 부르지 않는다"만 본다. (구현 계획에서 결정)
- 타임아웃 테스트는 `pg_transaction_id`에 요청의 `paymentKey`가 들어가는지 본다. 토스 클라이언트는 승인 응답의 `paymentKey`를 이 값으로 쓰므로(`TossPaymentClient`), 이 컬럼은 이미 `paymentKey`를 담고 있다. 가짜 PG는 `fake-UUID`를 돌려주므로, 재설계 후에는 PG 호출 전에 요청의 `paymentKey`로 채우는 쪽에 맞춘다.

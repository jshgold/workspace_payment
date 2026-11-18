# 주문/결제 도메인 재설계 논의 정리

> 상태: **논의 중 (코드 변경 없음)**. 아래 "미결정 사항"이 정해지면 구현 계획(상태 전이표, V9 마이그레이션, 변경 클래스/테스트 목록)을 먼저 확인받고 진행한다.
> 현재 phase는 phase-04이며, 다음 작업은 4-3(취소 연동)이다. 이 논의는 phase-03 결과를 바꾸는 리팩터링이라 4-3 범위 밖이다.

## 1. 출발점: 현재 구조에서 어색했던 점

주문/결제 코드를 읽고 확인한 점이다. (실행으로 검증한 것은 아니다.)

1. 재고 차감이 결제 승인 **뒤**에 일어난다. 재고 부족이 드러나면 이미 돈이 나간 뒤라 보상 취소로 가야 한다.
2. 재고 차감 로직이 두 군데다. phase-02의 `StockDecreaseService` 전략들은 결제 흐름에서 쓰이지 않고, `PaymentStateService.complete()`가 따로 차감한다.
3. `Payment`는 승인 성공 후에야 생성된다. 실패하면 아무 기록도 없어서 "결제를 했는지 안 했는지" 알 수 없다.
4. `PaymentFacade.complete()`의 `DataIntegrityViolationException` 처리는 PG 취소 없이 `PAYING`을 유지한다. 4-4 대사가 생기기 전에는 수동 처리뿐이다.
5. 주문 API가 `POST /api/orders` 하나뿐이다. (조회 없음, `PENDING` 주문 취소 수단 없음)
6. 비즈니스 오류를 `IllegalStateException`으로 던지고 `PaymentApprovalFacade`가 `STATE_CONFLICT`로 뭉뚱그린다. (CODE_CONVENTION 5번과 어긋남)
7. 진입점이 나뉘어 있다. 승인은 `PaymentApprovalFacade`(멱등키) → `PaymentFacade`, 취소는 멱등키 없이 `PaymentCancelFacade`.

이번 논의는 **7번 → 3번**을 중심으로 진행했고, 1·2·4·5·6번은 다루지 않았다.

## 2. 멱등키의 역할

- **이중 처리 방어(PG를 한 번만 호출):** `PENDING → PAYING` 선점과 주문 행 락이 이미 담당한다.
- **같은 요청에 같은 응답 반환(replay):** 선점으로는 못 한다. 응답을 못 받은 클라이언트가 재전송하면
  - 키 있음: 첫 결과(성공/확정 실패)를 그대로 받는다.
  - 키 없음: `startPaying()`이 상태 충돌(409)을 던져, 결제 성공 여부를 알 수 없다.
- 결론: 멱등키는 **replay용**이다. 이 도메인은 Order:Payment가 1:1이라 상태 기반 replay도 가능하지만, 키는 **유지하기로 했다.** (phase-04 4-5의 학습 포인트가 이 구조를 전제로 한다.)

## 3. 결정 1: 멱등키를 테이블이 아니라 `Payment` 컬럼으로

- 별도 `idempotency_key` 테이블(V5)은 두지 않고 `payment.idempotency_key`(UNIQUE) 컬럼으로 옮긴다.
- 단, 현재는 `Payment`가 승인 후에야 생기고 키는 PG 호출 **전에** 선점해야 한다. 그래서 `Payment`를 PG 호출 전에 먼저 만들어야 한다.
- 이것은 "실패하면 아무것도 안 생긴다"는 문제(3번)와 같은 원인이다. **`Payment`를 "승인된 결과"가 아니라 "결제 시도 기록"으로 바꾸면** 두 문제가 함께 풀린다.

## 4. 결정 2: 1:N 대신 "실패 시 주문 종료(FAILED)"

검토한 대안과 기각 이유:

| 안 | 내용 | 문제 |
|---|---|---|
| 1:N (시도마다 Payment 행) | 시도 이력이 모두 남는다 | 취소 시 "마지막(성공) 시도"를 찾는 쿼리가 필요하고, MySQL에 부분 유니크 인덱스가 없어 "APPROVED는 하나만" 보장에 별도 장치가 필요 |
| 1:1 + 행 재사용 | 스키마 단순 | 재시도 때 이전 실패 이력과 키가 덮어써져 "이 키는 이미 실패했다" replay 불가 |
| **1:1 + 확정 실패 시 Order/Payment를 FAILED로 종료 (채택)** | 재시도는 새 주문 | 사용자가 새 주문을 만들어야 함 (재고를 주문 시점에 선점하지 않으므로 비용은 작음) |

채택안의 형태:

- `startPaying` 트랜잭션에서 `Payment`를 `REQUESTED`로 함께 INSERT한다. (`idempotency_key`, `paymentKey`, 금액 저장)
- 결과별 상태:
  - 승인 반영: `Payment=APPROVED`, `Order=PAID`
  - 명확한 거절 / 금액 불일치(보상 취소 성공 후) / 반영 실패(보상 취소 성공 후): `Payment=FAILED`(+`failure_code`), `Order=FAILED`
  - 타임아웃(결과를 모름): `Payment=REQUESTED`, `Order=PAYING` 유지
  - 취소 완료: `Payment=CANCELED`, `Order=CANCELED`
- 같은 키 재요청은 키로 `Payment`를 찾아 응답한다: `APPROVED` → 첫 결과, `FAILED` → 같은 실패, `REQUESTED` → `PAYMENT_IN_PROGRESS`.
- 취소는 `findByOrderId`로 끝난다. (1:1 유지, `uk_payment_order_id` 유지)

상태 머신 변화:

- 승인: `PENDING → PAYING → PAID | FAILED` (기존의 `PAYING → PENDING` 간선 제거)
- 취소: `PAID → CANCELING → CANCELED | PAID` (PG가 취소를 명확히 거절한 경우 `PAID` 복구, 기존과 동일)

기대 효과:

- "결제를 했는지 모른다"가 해소된다. `FAILED`는 돈이 안 나간 것이 확정, `REQUESTED`가 남아 있으면 모르는 상태다.
- 4-4 대사는 `REQUESTED` + 저장된 `paymentKey`로 PG를 조회하면 된다.
- `IdempotencyService`, 키 엔티티/테이블, `PaymentApprovalFacade`의 키 처리가 사라지고 `startPaying`과 키 선점이 한 트랜잭션으로 합쳐진다.

## 5. PG의 같은 `orderId` 재사용 우려 → 해당 없음

- PG는 우리 쪽 재시도 개념을 모르고 들어온 요청만 본다.
- 이 설계에서는 재시도가 곧 새 주문이고 새 `orderNo`(UUIDv7)가 새 `orderId`로 나가므로, 실패한 `orderId`가 PG에 다시 가지 않는다.
- 같은 주문의 재요청(타임아웃 후 재전송)은 `REQUESTED` 상태에서 우리가 `PAYMENT_IN_PROGRESS`로 막아 PG까지 가지 않는다.
- 같은 `orderId`로 PG를 다시 호출하는 경우는 4-4(조회/대사)와 4-5(PG 멱등 키)뿐이며, 이 둘은 "같은 건을 다시 확인한다"는 의미라 설계와 충돌하지 않는다. PG 멱등 키 동작은 4-5에서 공식 문서로 확인한다.

## 6. 미결정 사항

1. **`PaymentGatewayException`(우리 연동 문제로 승인 단계에 못 간 경우)을 `FAILED`로 닫을지.**
   - 추천: 닫는다. 사용자가 고칠 수 있는 오류가 아니고, `PAYING → PENDING` 간선이 상태 머신에서 완전히 사라진다.
   - 안 닫으면 일시적 연동 오류에도 `PENDING` 복구 경로가 남아 상태 머신이 다시 복잡해진다. (닫으면 일시적 오류에도 새 주문이 필요해진다.)
2. **이 작업을 phase-04에 끼워 넣을지, 따로 다룰지.**
3. **취소에도 같은 멱등키 구조를 적용할지.** 취소도 환불이라 응답 유실이 치명적이므로 "승인에만 키가 있는" 비대칭의 이유가 약하다. 이번 결정과 별개로 정해야 한다.

## 7. 구현 시 영향 범위 (확정 아님, 구현 전 상세 확인 예정)

- **스키마:** 새 마이그레이션 V9로 변경한다. (V4/V5는 수정하지 않는다.)
  - `payment`에 `status`, `idempotency_key`(UNIQUE), `failure_code`, `paymentKey` 컬럼을 추가한다. 실패 건은 PG 거래 ID가 없으므로 `pg_transaction_id`의 NOT NULL 여부도 조정한다.
  - 기존 `payment` 행은 `APPROVED`로, `idempotency_key` 테이블 데이터는 `payment.idempotency_key`로 이관한다.
  - `orders.status`에 `FAILED`를 추가한다.
- **문서 수정:**
  - `.harness/phases/phase-04.md` 4-4 완료 조건: "승인되지 않은 건이면 `PENDING`으로 복구" → `FAILED`로 종료
  - `.harness/intent.md`: `OrderStatus`에 `FAILED` 추가, "Payment는 Order와 1:1" 설명을 "결제 시도 기록"으로 갱신
- **코드:** `Order`/`Payment` 도메인 메서드, `PaymentStateService` 구현체 2종(비관적 락/Atomic Update), `PaymentFacade`, `PaymentApprovalFacade`(축소 또는 제거), `IdempotencyService`/`IdempotencyKey*`(제거), 관련 테스트
- 공유 코드를 바꾸므로 구현 후에는 기존 락 전략 테스트(비관적 락/Atomic Update 결제 흐름·승인 테스트)도 회귀로 함께 실행한다.

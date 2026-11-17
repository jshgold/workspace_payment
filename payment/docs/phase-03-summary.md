# 주문·결제 정리 (phase-03)

> 코드(`PaymentFacade`, `PaymentApprovalFacade`, `PaymentCancelFacade`, 상태 서비스, 마이그레이션 V3~V6)를 읽고 정리한 문서입니다.
> **흐름, 설계 사실, 테스트 목록은 Claude가 채웠고, `내 생각`·`phase-04에서 확인할 가정에 대한 판단`은 직접 작성**합니다. 비어 있는 칸은 `(직접 작성)`입니다.
> 이 문서를 쓰면서 테스트를 다시 실행하지는 않았습니다. 테스트 이름은 코드에서 읽은 것입니다.
> phase-04에서 실제 PG를 붙인 뒤 "가짜 PG에서는 맞았는데 실제에서 깨진 가정"을 비교하는 기준선 용도입니다.

## 1. 주문 상태 전이

```
PENDING ──startPaying──▶ PAYING ──markPaid──▶ PAID ──startCanceling──▶ CANCELING ──markCanceled──▶ CANCELED
   ▲                        │                   ▲                          │
   └──revertToPending───────┘                   └──────revertToPaid────────┘
```

| 전이 | 호출 시점 | 의미 |
|---|---|---|
| PENDING → PAYING | PG 호출 **전** | 이 요청만 PG를 부를 수 있다는 선점 |
| PAYING → PAID | PG 승인 후 짧은 트랜잭션 | 결제 반영 (Payment 저장, 재고 차감 포함) |
| PAYING → PENDING | 돈이 나가지 않은 것이 **확실**할 때만 | PG 거절, 보상 취소 성공 |
| PAID → CANCELING | PG 취소 호출 **전** | 취소 선점 |
| CANCELING → CANCELED | PG 취소 성공 후 짧은 트랜잭션 | 재고 복구 포함 |
| CANCELING → PAID | PG가 취소를 **명확히 거절**했을 때만 | 결제가 살아 있으므로 재시도 허용 |

`PAYING`, `CANCELING`은 "PG 호출 중, 결과를 아직 모름"을 나타내는 중간 상태이고, 서버가 이 상태에서 죽으면 복구(대사)가 필요합니다. 이 점이 phase-04 4-4의 출발점입니다.

## 2. 결제 승인 흐름 (`PaymentApprovalFacade` → `PaymentFacade`)

```
[PaymentApprovalFacade, @Transactional 없음]
 1. idempotencyService.begin      (짧은 트랜잭션, 주문 행 FOR UPDATE) 키 선점 / 재요청 응답 / 요청 금액 검증
 2. PaymentFacade.approve
      a. startPaying              (짧은 트랜잭션) PENDING → PAYING
      b. paymentClient.approve    (트랜잭션 밖)   DB 커넥션을 쥐지 않음
      c. 승인 금액 ≠ 서버 금액    → 보상 취소 후 PENDING 복구, PAYMENT_AMOUNT_MISMATCH
      d. complete                 (짧은 트랜잭션) PAID + Payment 저장 + 재고 차감 + 이벤트 발행
      e. d 실패 → 보상 취소(PG) → 취소 성공해야만 PENDING 복구
 3. succeed / fail                (짧은 트랜잭션) 첫 요청의 결과를 키에 기록
```

### 실패 유형별 처리: 기준은 "돈이 나갔는지 아는가"

| 상황 | 돈 | 주문 상태 | 멱등키 | 이유 |
|---|---|---|---|---|
| PG 명확한 거절 | 안 나감 | PENDING 복구 | FAILED | 재시도 가능 |
| PG 타임아웃 / 알 수 없는 예외 | **모름** | PAYING 유지 | IN_PROGRESS 유지 | 되돌리면 승인된 결제를 놓칠 수 있음 |
| 승인 금액 불일치 | 나감 | 보상 취소 성공 시 PENDING | FAILED | 취소해서 돈을 돌려놔야 함 |
| 승인 후 DB 반영 실패 | 나감 | 보상 취소 성공 시 PENDING | FAILED(예외 종류에 따라) | 취소로 돈을 돌려놔야 함 |
| 보상 취소마저 실패 | 묶임 | PAYING 유지 | IN_PROGRESS 유지 | 추적할 수 있게 남김 |
| `DataIntegrityViolationException` | 이미 처리됐을 수 있음 | PAYING 유지 | IN_PROGRESS 유지 | 정상 결제를 취소하면 안 되므로 PG 취소 안 함 |

## 3. 결제 취소 흐름 (`PaymentCancelFacade`)

```
 1. startCanceling   (짧은 트랜잭션) PAID → CANCELING
 2. paymentClient.cancel (트랜잭션 밖)
 3. completeCancel   (짧은 트랜잭션) CANCELED + 재고 복구 (이미 CANCELED면 재고를 또 올리지 않음)
```

- `PaymentCancelFailedException`(명확한 실패): `PAID`로 복구, 재시도 가능
- 타임아웃 등: `CANCELING` 유지 (환불됐을 수 있으므로)
- PG 취소 성공 후 DB 반영 실패: 이미 환불됐으므로 되돌릴 수 없어 `CANCELING` 유지 + 로그
- 잠금 순서는 승인과 같은 **orders → product**여서, 취소와 승인이 같은 상품을 동시에 건드려도 데드락이 나지 않는 구조입니다.

## 4. 작업별 정리

| 작업 | 만든 것 | 학습 포인트에 대한 사실 |
|---|---|---|
| 3-1 주문 생성 | `Order.create`: 상태 PENDING, 금액 = `단가 × 수량`을 서버가 계산(`Math.multiplyExact`) | 클라이언트 금액을 받지 않음. 주문 생성 시 재고는 **차감하지 않고 사전 검증만** 함 |
| 3-2 가짜 PG | `PaymentClient`, `FakePaymentClient`(승인/거절/타임아웃, 지연, 취소 실패, 호출 횟수 기록) | PG 호출이 `@Transactional` 안에 있으면 지연 시간만큼 커넥션을 점유함 → 3-3에서 Facade로 분리 |
| 3-3 결제 승인 | `PaymentFacade`, `PaymentStateService`(락 전략별 구현체 2개) | 선 외부 I/O → 후 짧은 트랜잭션. 보상 취소 포함 |
| 3-4 멱등성 | `payment.order_id` UNIQUE, `Idempotency-Key` 헤더, `idempotency_key` 테이블, `PaymentApprovalFacade` | 아래 5번 참고 |
| 3-5 결제 취소 | `PaymentCancelFacade`, `PaymentCancelStateService`, `CANCELING` 상태(V6) | `PAID`만 취소 가능. 재취소 시 PG 재호출 없음, 재고는 한 번만 복구 |
| 3-6 결제 완료 이벤트 | `PaymentCompletedEvent`, `@TransactionalEventListener(AFTER_COMMIT)` + `@Async("paymentEventExecutor")`, `AsyncConfig` | 롤백되면 후처리 안 됨. 전용 스레드 풀 사용 |

### 상태 서비스의 두 구현체 (같은 Facade, 같은 테스트 공유)

| 구현 | 선점 방식 | 비고 |
|---|---|---|
| `PessimisticLockPaymentStateService` (`@Primary`) | 주문 행 `FOR UPDATE` 후 엔티티 상태 전이 | phase-02 결론(비관적 락 기본)에 따라 기본 구현 |
| `AtomicUpdatePaymentStateService` | `UPDATE orders SET status='PAYING' WHERE id=? AND status='PENDING'` 0행이면 선점 실패 | 상태 규칙이 SQL에도 들어감. 재고 차감을 트랜잭션 맨 끝에 둬 재고 행 락 보유 시간을 줄임 |

## 5. 멱등성은 두 겹이다

| 장치 | 막는 것 | 못 막는 것 |
|---|---|---|
| `payment.order_id` UNIQUE | 같은 주문에 결제 **기록**이 두 개 생기는 것 (DB 마지막 방어선) | **PG가 이미 두 번 호출된 것.** UNIQUE 위반은 PG 승인 이후에 발생함 |
| 주문 선점 (`PENDING → PAYING`) | 같은 주문에 PG를 **두 번 부르는 것** | 클라이언트 재전송에 첫 결과를 돌려주는 것 |
| `Idempotency-Key` + `idempotency_key` 테이블 | 같은 키의 재요청에 첫 결과를 재생(SUCCEEDED) 또는 `409 PAYMENT_IN_PROGRESS`, 같은 키를 다른 주문에 쓰는 것(`IDEMPOTENCY_KEY_REUSED`) | 키가 다른 새 시도 (`STATE_CONFLICT`를 받음) |

키 선점(`begin`)은 아직 존재하지 않는 키 행을 잠글 수 없어서, 이미 있는 **주문 행을 FOR UPDATE로 잠가 출입문**으로 씁니다. 이 락은 `begin` 트랜잭션이 끝나면 풀리고 PG 호출 구간에는 쥐고 있지 않습니다.

## 6. 이벤트 (3-6)

- 발행은 `complete` 트랜잭션 안에서, 재고 차감 **전**에 합니다. 처리는 커밋 이후(`AFTER_COMMIT`)이므로 재고 부족으로 롤백되면 이벤트는 버려집니다.
- `@Async`가 필요한 이유: `AFTER_COMMIT` 시점에는 발행 스레드에 기존 트랜잭션 리소스가 아직 묶여 있어, 리스너에서 `REQUIRED`로 DB에 쓰면 커밋되지 않고 조용히 유실됩니다.
- 후처리 실패는 이미 커밋된 결제에 영향을 주지 않고 로그만 남깁니다.
- 한계: 이벤트는 **프로세스 메모리**에 있어서, 커밋 직후 서버가 죽으면 후처리가 유실됩니다. (유실 불가라면 Outbox가 필요하다는 ARCHITECTURE 5-3 영역)

## 7. 테스트로 확인한 시나리오

| 영역 | 테스트 클래스 | 확인하는 것 (코드에서 읽은 DisplayName 기준) |
|---|---|---|
| 승인 오케스트레이션 | `PaymentFacadeTest` | 정상 승인, 거절 → PENDING, 타임아웃 → PAYING 유지, 금액 불일치 → 보상 취소, 반영 실패 → 보상 취소, 보상 실패 → PAYING 유지, 무결성 위반 → PG 취소 안 함 |
| 멱등키 | `PaymentApprovalFacadeTest`, `IdempotencyKeyTest` | 성공 기록, 같은 키 재요청 시 첫 결과 재생, 확정 실패 FAILED 기록, 결과를 모르는 실패는 IN_PROGRESS 유지 |
| 승인 동시성·흐름 | `PessimisticLockPaymentApprovalTest` / `AtomicUpdatePaymentApprovalTest`, `*PaymentFlowTest` | 두 구현체가 같은 시나리오를 공유 (`PaymentApprovalTestSupport`, `PaymentFlowTestSupport`) |
| 취소 | `PaymentCancelTest` | 정상 취소와 재고 복구, 재취소 실패, PENDING 취소 불가, PG 취소 실패 시 PAID 복구, 품절 상품 취소 시 판매중 복귀, 10건 동시 취소 시 1건만 성공, 취소 5건 + 승인 5건 동시 실행 시 데드락 없음 |
| 이벤트 | `PaymentCompletedEventTest` | 커밋 시 1번·전용 스레드에서 실행, 롤백 시 미실행, 중복 `complete`에도 1번만 실행 |

## 8. 학습 포인트: 내 생각

1. 결제 금액을 클라이언트 값으로 믿으면 어떤 공격이 가능한가? (3-1)
   - (직접 작성)
2. PG 응답이 3초 걸릴 때 DB 커넥션을 쥐고 있으면 어떻게 되나? (3-2)
   - (직접 작성)
3. 하나의 `@Transactional`에 PG 호출을 넣으면 왜 안 되나? (3-3)
   - (직접 작성)
4. DB UNIQUE 제약만으로는 왜 부족한가? (3-4) → 5번 표 참고
   - (직접 작성)
5. 결제 취소와 재고 차감이 동시에 일어나면? (3-5)
   - (직접 작성)
6. `@Async` 없이 AFTER_COMMIT 리스너에서 DB에 쓰면 왜 반영이 안 되나? (3-6) → 6번 참고
   - (직접 작성)

## 9. 마무리 질문의 현재 답

> PG 승인은 성공했는데 서버가 응답 직전에 죽었다. 어떻게 복구할 것인가?

- **현재 코드로는 복구할 수 없습니다.** 주문은 `PAYING`, 멱등키는 `IN_PROGRESS`로 남고, `PaymentClient`에 결제 조회 메서드가 없어 확인할 수단도 없습니다. (`docs/known-limitations.md` 2·3·7·9번)
- 이어지는 작업: phase-04의 4-4(결제 조회와 `PAYING` 대사). 대사가 `PAYING`을 확정할 때 멱등키 상태도 함께 확정해야 합니다.
- 내 생각: (직접 작성)

## 10. phase-04에서 확인할 가정 (가짜 PG 기준으로 코드에 들어 있는 것)

| # | 현재 가정 | 실제 PG에서 확인할 것 |
|---|---|---|
| 1 | 승인 요청은 `orderId`와 `amount`만 보냄 (`PgApproveRequest`) | 실제 PG가 요구하는 식별자(예: `paymentKey`)와 필드 |
| 2 | 응답은 `pgTransactionId`와 `approvedAmount` (`PgApproveResult`) | 응답 형식, 승인 금액 필드 |
| 3 | "명확한 거절"은 `PaymentDeclinedException` 하나로 표현 | 4xx 에러 코드 중 거절과 그 외(잘못된 요청, 이미 처리됨)의 구분 |
| 4 | "결과를 모름"은 `PaymentTimeoutException`과 알 수 없는 예외 | 5xx, 읽기 타임아웃, 연결 실패를 어디에 분류할지 |
| 5 | 취소 실패는 `PaymentCancelFailedException` 하나이고 "결제가 살아 있다"로 간주해 `PAID`로 복구 (사용자 취소), 보상 취소에서는 `PAYING` 유지 | 이미 취소된 건에 대한 응답, 취소 타임아웃 시 PG 쪽 상태 |
| 6 | PG 멱등 키 없음. 우리 쪽 `Idempotency-Key`만 있음 | PG 멱등 키 헤더 지원 여부와 우리 키와의 차이 |
| 7 | 승인 금액은 `long` 정수 비교 | 통화/소수점 처리 |

- 가짜 PG에서는 통과했는데 실제 PG에서 처음 깨진 가정은 무엇이었나? → phase-04 완료 후 여기에 기록: (직접 작성)

## 11. 남은 한계

`docs/known-limitations.md`에 10개 항목으로 정리돼 있습니다. phase-04에서 해결되는 것은 2·3·7·9번(조회와 대사)이고, 나머지(PENDING 정리 배치, 주문 상품 스냅샷, 멱등키 정리 등)는 phase-04 범위 밖입니다.

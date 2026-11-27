# Intent

## 1. 서비스 요약과 목적
상품을 등록·관리하고, 주문을 결제하는 커머스 백엔드.
**목적은 동시성 제어(락) 학습이다.** 기능을 넓히기보다, 같은 문제를 여러 락 전략으로 풀어보고 테스트로 비교하는 데 집중한다.

## 2. 범위 전제
- 인증·인가는 만들지 않는다. 사용자 식별이 필요한 API는 요청에 `memberId`를 직접 받는다.
- 주문 하나에는 상품 하나만 담는다. (수량은 여러 개 가능)

## 3. 단계 (상세는 `.harness/phases/`)
- phase-00: 프로젝트 환경 세팅
- phase-01: 상품 CRUD
- phase-02: 재고 차감과 락 전략 비교
- phase-03: 주문·결제
- phase-04: 실 PG 테스트 연동 (결제 모델 재설계 포함)

## 4. 도메인 용어
- **Product:** 판매 상품. 이름, 가격, 재고, 판매 상태를 가진다.
- **ProductStatus:** `ON_SALE`(판매중) / `SOLD_OUT`(품절) / `STOPPED`(판매중지)
- **Order:** 사용자가 상품을 사려는 요청 하나.
- **OrderStatus:**
  - `PENDING`(결제 대기. 결제를 아직 시작하지 않음)
  - `PAYING`(결제 진행 중. PG 호출이 실제로 나가 있고 응답을 기다리는 중)
  - `PAYMENT_UNKNOWN`(결제 결과 모름. 타임아웃·5xx로 응답을 놓쳐 대사를 기다리는 중)
  - `PAID`(결제 완료)
  - `FAILED`(결제 실패로 종료. 돈이 나가지 않았음이 확정된 상태)
  - `EXPIRED`(만료. 결제를 시작하지 않은 채 버려진 주문)
  - `CANCELING`(취소 진행 중. PG 취소 호출이 나가 있고 응답을 기다리는 중)
  - `CANCEL_UNKNOWN`(취소 결과 모름. 타임아웃·5xx로 응답을 놓쳐 대사를 기다리는 중)
  - `CANCEL_REVIEW`(취소 확인 필요. 취소 대사 조회에서 토스가 `NOT_FOUND_PAYMENT`를 줬다. 이미 결제된 건이 조회되지 않는 장애라 대사 대상에서 빼고 사람이 확인한다)
  - `CANCELED`(취소 완료)
- **Payment:** Order에 대한 **결제 시도 기록**. 승인 결과가 아니라 시도를 기록한다.
  - PG를 호출하기 **전에** `REQUESTED`로 생성한다. (멱등키, `paymentKey`, 금액 저장)
  - `paymentKey`는 결제창 인증 성공 시 클라이언트가 받아 승인 요청에 담아 보내는 값이므로, `REQUESTED` 생성 시점에 항상 채울 수 있다. (NOT NULL)
  - Order와 1:1이다. 재시도는 새 주문으로 하므로 한 주문에 결제 시도는 하나뿐이다.
- **PaymentStatus:**
  - `REQUESTED`(PG 승인 요청 중)
  - `UNKNOWN`(승인 결과 모름. 대사로 확정)
  - `APPROVED`(승인)
  - `FAILED`(실패 확정. `failure_code` 기록)
  - `CANCELED`(취소)
- **PaymentCancel:** 사용자 결제 취소 요청의 **취소 시도 기록**.
  - PG 취소를 호출하기 **전에** `REQUESTED`로 생성한다. (멱등키, 취소 사유 저장)
  - Order와 1:N이다. PG가 취소를 거절하면 주문이 `PAID`로 돌아가 새 키로 다시 취소할 수 있기 때문이다. 진행 중인 시도가 하나뿐인 것은 주문 상태 선점(`PAID → CANCELING`)이 보장한다.
  - 보상 취소(승인 금액 불일치)는 기록하지 않는다. 결과는 Payment(`FAILED` 또는 `UNKNOWN` + `PAYMENT_CANCEL_FAILED`)에 남는다.
  - 주문 상태가 바뀌지 않는 거절(이미 `CANCELED`, 취소할 수 없는 상태)은 시도가 아니므로 기록하지 않는다.
- **PaymentCancelStatus:**
  - `REQUESTED`(PG 취소 요청 중)
  - `UNKNOWN`(취소 결과 모름. 대사로 확정)
  - `COMPLETED`(취소 완료. PG `ALREADY_CANCELED_PAYMENT`·`ALREADY_REFUND_PAYMENT` 포함)
  - `FAILED`(취소 실패 확정. 결제는 살아 있다. `failure_code` 기록)
- **"진행 중"과 "결과 모름"을 구분하는 이유:** 대사 대상을 시간 추측이 아니라 상태로 고를 수 있고, 화면에서 "결제 중"과 "결제 확인 중"을 나눌 수 있으며, 결과 모름 건수가 PG 장애 지표가 된다.

## 5. 비즈니스 규칙

### 상품
- 가격은 0보다 커야 하고, 재고는 0 이상이어야 한다.
- 재고가 0이 되면 상태는 `SOLD_OUT`이 된다.
- `STOPPED` 상품은 목록 조회에 노출하지 않는다.
- 삭제는 소프트 삭제로 처리한다. (주문 이력에서 참조되기 때문)

### 재고·주문·결제
- 동시 주문이 몰려도 재고는 절대 음수가 되면 안 된다.
- 결제 금액은 서버가 주문 정보로 계산한 금액과 PG 승인 금액을 비교해 검증한다.
- 같은 주문에 대한 결제 승인은 한 번만 성공해야 한다. (멱등성)
- 결제 취소 시 재고를 복구한다.
- 결제가 확정적으로 실패하면 주문은 `FAILED`로 종료한다. 다시 결제하려면 새 주문을 만든다. (실패 화면의 "다시 결제"는 실패한 주문을 복사해 새 주문을 만든다)
- PG 응답을 받지 못한 경우(타임아웃, 응답 유실, 5xx)는 실패가 아니라 **결과를 모르는 상태**다. `PAYMENT_UNKNOWN`으로 전이하고 대사로 확정한다.
- 결과를 모르는 주문(`PAYING`, `PAYMENT_UNKNOWN`)이 있는 동안에는 화면에서 "결제 확인 중"을 보여주고 다시 결제를 막는다. (승인됐을 수도 있는 결제를 두고 새 주문을 결제하면 이중 결제가 된다)
- 결제를 시작하지 않은 `PENDING` 주문은 일정 시간이 지나면 `EXPIRED`로 만료한다. 주문 생성은 아무것도 소모하지 않으므로 중복 주문은 막지 않고 만료로 정리한다.

### 상태 전이 (아래 간선 외의 전이는 금지한다)
| 흐름 | 전이 |
|---|---|
| 승인 시작 | `PENDING → PAYING` |
| 결제 시작 거절 (PG 호출 전: 재고 부족, 요청 금액 불일치) | `PENDING → FAILED` |
| 승인 결과 | `PAYING → PAID` / `PAYING → FAILED` / `PAYING → PAYMENT_UNKNOWN` |
| 결과 모름 대사 | `PAYMENT_UNKNOWN → PAID` / `PAYMENT_UNKNOWN → FAILED` |
| 서버 중단 추정 대사 | `PAYING`(N분 경과) `→ PAID` / `→ FAILED` |
| 만료 | `PENDING → EXPIRED` (N분 동안 결제를 시작하지 않음) |
| 취소 시작 | `PAID → CANCELING` |
| 취소 결과 | `CANCELING → CANCELED` / `CANCELING → PAID`(PG가 명확히 거절) / `CANCELING → CANCEL_UNKNOWN` |
| 취소 결과 모름 대사 | `CANCEL_UNKNOWN → CANCELED` / `CANCEL_UNKNOWN → PAID` / `CANCEL_UNKNOWN → CANCEL_REVIEW`(조회 `NOT_FOUND_PAYMENT`) |
| 서버 중단 추정 대사 | `CANCELING`(N분 경과) `→ CANCELED` / `→ PAID` / `→ CANCEL_REVIEW`(조회 `NOT_FOUND_PAYMENT`) |
| 취소 확인 후 재대사 | `CANCEL_REVIEW → CANCEL_UNKNOWN` (사람이 원인을 고친 뒤 수동 API. 다음 대사가 다시 조회해 확정) |

- `FAILED`, `CANCELED`, `EXPIRED`는 최종 상태다. 여기서 다른 상태로 가지 않는다.
- `PAYING → PENDING`처럼 **결제 시도 이전 상태로 되돌리는 간선은 없다.**
  - 예외: `CANCELING → PAID`, `CANCEL_UNKNOWN → PAID`. 취소 시도가 무산된 것(PG 거절, 대사로 취소되지 않았음을 확인)이지 확정된 결과를 지우는 것이 아니다. 취소 시도와 거절 사유는 기록으로 남긴다. (4-3)
- **재고는 결제 시작(`PENDING → PAYING`) 트랜잭션에서 차감한다(선차감).** 재고가 모자라면 PG를 호출하지 않고, 같은 트랜잭션에서 주문을 `FAILED`로 바꾸고 Payment를 `FAILED`(`STOCK_NOT_AVAILABLE`)로 남긴 뒤 커밋한다.
  - 요청 금액이 서버 계산 금액과 다를 때도 같다. (`PAYMENT_REQUEST_AMOUNT_MISMATCH`, 클라이언트가 보낸 금액은 로그로 남긴다)
  - PG를 부르지 않았어도 결제 시도는 있었으므로 기록한다. 같은 키의 재요청은 같은 실패를 받고, 새 키는 `ORDER_NOT_PAYABLE`을 받는다.
  - `→ PAID` 전이(승인, 대사)에서는 재고를 건드리지 않는다.
  - `→ FAILED` 전이(승인 실패, 대사, 보상 취소 후)에서는 같은 트랜잭션에서 재고를 복구한다.
  - `PAYING`, `PAYMENT_UNKNOWN`에 머무는 동안은 재고를 쥐고 있다. 대사 기준 시간 N을 정할 때 이 점을 고려한다.
- `PAYING → PAYMENT_UNKNOWN` 전이는 서버가 타임아웃을 감지했을 때만 기록된다. PG 호출 직후 서버가 죽으면 `PAYING`에 남으므로, 대사는 `PAYMENT_UNKNOWN`과 오래된 `PAYING`을 모두 본다.
- 만료(`PENDING → EXPIRED`)와 결제 시작(`PENDING → PAYING`), 대사와 늦게 도착한 응답처럼 여러 경로가 같은 주문을 바꿀 수 있으므로, 모든 전이는 현재 상태를 조건으로 건다.

| Payment 흐름 | 전이 |
|---|---|
| 생성 | 결제 시작 시 `REQUESTED`로 생성 / PG 호출 전 거절이면 처음부터 `FAILED`로 생성 |
| 승인 결과 | `REQUESTED → APPROVED` / `REQUESTED → FAILED` / `REQUESTED → UNKNOWN` |
| 결과 모름 대사 | `UNKNOWN → APPROVED` / `UNKNOWN → FAILED` |
| 서버 중단 추정 대사 | `REQUESTED`(N분 경과) `→ APPROVED` / `→ FAILED` |
| 취소 | `APPROVED → CANCELED` |

- PG가 승인했지만 금액이 서버 금액과 달라 보상 취소한 경우는 `FAILED` + `failure_code`(`PAYMENT_AMOUNT_MISMATCH`)로 남긴다.
- PG가 서버 금액 그대로 승인했는데 우리 DB 반영(`complete`)만 실패하면 **보상 취소하지 않는다.** 고객이 동의했고 돈도 나간 정상 결제이므로 결과 모름(`PAYMENT_UNKNOWN` / `UNKNOWN`, `failure_code` 없음, `approved_amount` 기록)으로 남기고 대사가 `PAID`로 확정한다. 그 기록마저 실패하면 `PAYING`에 남아 오래된 `PAYING`으로 대사된다. (2026-11-26 변경: 처음에는 보상 취소였다)
- 보상 취소 자체가 실패하면 돈이 묶였는지 모르므로 `UNKNOWN`(주문 `PAYMENT_UNKNOWN`) + `failure_code=PAYMENT_CANCEL_FAILED`로 남겨 대사 대상으로 만든다. PG가 승인한 금액은 `approved_amount`에 남긴다. 대사는 승인 여부뿐 아니라 금액까지 비교해야 한다.
  - 대사 처리 (4-4): PG 조회 `CANCELED`면 `FAILED` + 재고 복구. `DONE`이고 금액이 서버 금액과 같으면(2026-11-26 이전에 반영 실패로 보상하려던 건) `PAID`로 확정, 다르면(금액 불일치) 다시 취소하고 성공하면 `FAILED` + 재고 복구.

| PaymentCancel 흐름 | 전이 | 함께 바뀌는 것 |
|---|---|---|
| 생성 | 취소 선점 시 `REQUESTED`로 생성 | 주문 `PAID → CANCELING` (같은 트랜잭션) |
| 취소 결과: 성공 | `REQUESTED → COMPLETED` | 주문 `CANCELING → CANCELED`, Payment `APPROVED → CANCELED`, 재고 복구 |
| 취소 결과: 명확한 거절 | `REQUESTED → FAILED` | 주문 `CANCELING → PAID` |
| 취소 결과: 모름 | `REQUESTED → UNKNOWN` | 주문 `CANCELING → CANCEL_UNKNOWN` |
| 결과 모름 대사 (4-4) | `UNKNOWN → COMPLETED` / `UNKNOWN → FAILED` | 주문 `CANCEL_UNKNOWN → CANCELED`(재고 복구) / `→ PAID` |
| 서버 중단 추정 대사 (4-4) | `REQUESTED`(N분 경과) `→ COMPLETED` / `→ FAILED` | 주문 `CANCELING → CANCELED`(재고 복구) / `→ PAID` |
| 조회 `NOT_FOUND_PAYMENT` (4-4) | `REQUESTED → UNKNOWN` / `UNKNOWN` 그대로 | 주문 `CANCELING`·`CANCEL_UNKNOWN → CANCEL_REVIEW`. 결제 `APPROVED`, 재고 그대로 |
| 취소 확인 후 재대사 (4-4) | `UNKNOWN` 그대로 | 주문 `CANCEL_REVIEW → CANCEL_UNKNOWN` (수동 API) |

- PG 취소 응답이 `ALREADY_CANCELED_PAYMENT` 또는 `ALREADY_REFUND_PAYMENT`이면 성공으로 본다. 첫 시도에서 이 응답이 오면 `log.warn`을 남긴다.
- 같은 키 재요청: 다른 주문·사유면 `IDEMPOTENCY_KEY_REUSED`(422), `COMPLETED`면 첫 결과, `FAILED`면 같은 실패, `REQUESTED`/`UNKNOWN`이면 409 처리 중.
- 새 키 요청: 주문이 `PAID`면 새 시도, `CANCELING`/`CANCEL_UNKNOWN`/`CANCEL_REVIEW`이면 409 처리 중, `CANCELED`면 409 이미 취소됨, 그 밖의 상태면 409 취소할 수 없음. (기록하지 않음)
- `failure_code`에는 `ErrorCode` 이름을 저장한다. 같은 키의 재요청에 같은 에러를 돌려줄 때 그대로 쓴다.
- 취소 진행·결과 모름은 Order 상태(`CANCELING`, `CANCEL_UNKNOWN`)로 관리하고, Payment는 취소가 확정될 때 `CANCELED`가 된다.

## 6. 락 학습 방식
- 재고 차감은 한 가지 방식으로 단정하지 않는다. 아래 전략을 **각각 별도 구현체로** 만들어 같은 동시성 테스트로 비교한다.
  1. 락 없음 (문제 재현용)
  2. 조건부 Atomic Update (`UPDATE ... SET stock = stock - ? WHERE id = ? AND stock >= ?`)
  3. 비관적 락 (`PESSIMISTIC_WRITE`)
  4. 낙관적 락 (`@Version` + 재시도)
  5. MySQL Named Lock (`GET_LOCK` / `RELEASE_LOCK`)
  6. Redis 분산 락 (Redisson)
- 새 전략을 구현할 때는 ARCHITECTURE 7번에 따라 구현 전에 방식과 트레이드오프를 먼저 설명한다.
- 각 전략의 결과(성공 건수, 최종 재고, 소요 시간)를 테스트로 확인할 수 있어야 한다.

## 7. 하지 않는 것
- 인증·인가, 회원 관리
- 장바구니, 쿠폰, 포인트, 배송
- 여러 상품을 한 주문에 담기, 부분 취소, 정산
- Kafka (필요해지면 phase-03 이후 별도로 다룬다)

위 항목이 없어서 생기는 문제(예: 인증이 없어 누구나 남의 주문을 취소할 수 있음)는 이상 보고나 설계 검토에서 지적하지 않는다. 범위 밖인 것을 알고 정한 것이다.

## 8. 결정 기록
phase 파일의 "시작 전에 정할 것"이나 설계 변경으로 정한 내용은 여기에 남긴다. 이유는 사용자가 쓴다.

| 결정 | 선택 | 이유 | 시점 |
|---|---|---|---|
| PG사 | 토스페이먼츠 | 서버가 승인(confirm)을 호출하는 구조와 가장 가깝다 | phase-04 |
| 재고 차감 시점 | ~~결제 승인 시 차감 (후차감)~~ → **결제 시작(`startPaying`) 시 차감 (선차감)**, 실패 확정 시 복구 | (사용자 작성) 근거: 경합 100건에서 PG 승인/취소 100/99 → 1/0 (`docs/troubleshooting/payment-stock-deduction-timing.md`) | phase-03 → 변경: phase-04 트러블슈팅 개선 A |
| 결제 재고 차감에 쓸 락 전략 | (사용자 작성) | (사용자 작성) | phase-03 |
| Payment 모델 | 승인 결과 → **결제 시도 기록**으로 변경 | 결과를 모르는 상태와 실패 이력을 표현하기 위해 | phase-04 재설계 |
| 결제 실패 시 주문 처리 | `FAILED`로 종료, 재시도는 새 주문 (1:N 기각) | (사용자 작성) 주문번호를 하나로 유지해야 하는 요구가 생기면 1:N으로 전환한다. Payment가 시도 기록이라 전환 비용이 작다 | phase-04 재설계 |
| 멱등키 저장 위치 | 별도 테이블 → `payment.idempotency_key` 컬럼 | 키 선점과 결제 시도 기록을 한 트랜잭션으로 묶기 위해 | phase-04 재설계 |
| 결과 모름 상태 분리 | `PAYING`(진행 중)과 `PAYMENT_UNKNOWN`(결과 모름)을 분리, 취소도 `CANCELING`/`CANCEL_UNKNOWN`으로 분리 | 대사 대상을 상태로 고르고, 화면 문구와 장애 지표를 나누기 위해 | phase-04 재설계 |
| 버려진 주문 처리 | 주문 중복 생성은 막지 않고, 결제를 시작하지 않은 `PENDING`은 `EXPIRED`로 만료 | 주문 생성이 아무것도 소모하지 않으므로 | phase-04 |
| 같은 주문 중복 결제 요청 응답 | 락 획득 후 `PENDING`이 아니면 상태별로 응답: `PAYING`·`PAYMENT_UNKNOWN`·`PAID` → `PAYMENT_IN_PROGRESS`(409, 프론트는 무시), `FAILED`·`EXPIRED`·`CANCELING`·`CANCELED` → `ORDER_NOT_PAYABLE`(409, 새 주문 안내) | 화면은 첫 요청의 응답으로 그려지므로. (사용자 작성) | phase-04 재설계 (4-2+에서 분리) |
| PG 호출 전 거절 (재고 부족, 요청 금액 불일치) | 기록한다: 주문 `PENDING → FAILED`, Payment `FAILED` + `failure_code` | (사용자 작성) PG까지 가지 않았을 뿐 결제 시도는 있었다. 금액 불일치는 정상 흐름에서 생길 수 없다 | phase-04 4-2+ |
| 보상 취소 실패 | 주문 `PAYMENT_UNKNOWN`, Payment `UNKNOWN` + `failure_code=PAYMENT_CANCEL_FAILED` | (사용자 작성) | phase-04 4-2+ |
| `payment` 컬럼 | `pg_transaction_id` → `payment_key`, `approved_amount`·`updated_at` 추가 | (사용자 작성) | phase-04 4-2+ |
| `idempotency_key` 테이블 | V9에서 `payment.idempotency_key`로 이관 후 삭제 (사용자 승인 2026-11-20) | | phase-04 4-2+ |
| 같은 키 + 다른 금액 재요청 | 거절: `IDEMPOTENCY_KEY_REUSED`(422)를 같이 쓰고 메시지를 "같은 멱등키로 다른 내용의 요청이 왔습니다."로 넓힘. 단, 요청 금액 불일치(`PAYMENT_REQUEST_AMOUNT_MISMATCH`)로 실패한 시도는 금액을 비교하지 않고 같은 실패를 돌려준다 (클라이언트 금액을 저장하지 않아 재전송과 구별할 수 없으므로) | (사용자 작성) | phase-04 4-2+ (2026-11-20) |
| 품절 폭주 대응 | **락 전 재고 조회**: `startPaying`에서 상품 행을 잠그기 직전에 재고·상태·삭제 여부를 락 없이 값으로만 읽어, 확실히 안 되면 상품 행 락 없이 실패를 기록한다. 통과한 요청은 지금처럼 락 후 다시 판정한다. 스냅샷이 낡아 생기는 빡빡한 거절은 감수한다. 비관적 락 구현에만 넣고, 원자적 쿼리 구현에는 넣지 않는다 (`docs/troubleshooting/payment-precheck-stock.md`) | (사용자 작성) | phase-04 트러블슈팅 (2026-11-24) |
| PG "이미 취소된 결제" 응답 (`ALREADY_CANCELED_PAYMENT`, `ALREADY_REFUND_PAYMENT`) | 취소 성공으로 간주: `CANCELED` + 재고 복구. 부분 취소가 없으므로 "이미 취소됨" = 전액 환불됨. 주로 취소 응답 유실 후 다시 취소할 때 생기고, 첫 취소에서 오면 우리가 모르는 경로(상점관리자 등)로 취소된 것이므로 `log.warn`을 남긴다 | (사용자 작성) | phase-04 4-3 (2026-11-25) |
| 취소 멱등키 적용 | 적용: 취소 요청에 `Idempotency-Key` 필수. 같은 키 + 같은 내용은 첫 결과 replay, 같은 키 + 다른 주문·사유는 `IDEMPOTENCY_KEY_REUSED`(422) 거절 + `log.warn`. 중복 PG 호출은 상태 선점(`PAID → CANCELING`)이 막고, 키는 재시도와 새 요청을 구분해 replay와 이상 감지를 맡는다 (phase-03의 "취소는 키 없음" 결정을 바꿈) | (사용자 작성) | phase-04 4-3 (2026-11-25) |
| 취소 시도 기록 | `payment_cancel` 테이블 신설(V10, Order 1:N, `idempotency_key` UNIQUE). 사용자 취소만 기록하고 보상 취소는 기록하지 않음. 주문 상태가 바뀌지 않는 거절(이미 `CANCELED` 등)은 기록 없이 409 | (사용자 작성) | phase-04 4-3 (2026-11-25) |
| 실제 PG 확인 시점 | 4-3~4-5는 가짜 PG로 개발하고, 실제 토스 호출 확인은 4-6 수동 E2E에 모아서 한다 | (사용자 작성) 이전 테스트 중 토스에서 요청이 차단된 적이 있다 | phase-04 4-3 (2026-11-25) |
| 대사의 역할 | **조회만 한다.** PG 결제 조회로 이미 일어난 결과를 확인해 기록을 맞추고, 승인 요청은 다시 보내지 않는다. 결제 주체는 고객이고 결제 의사는 요청한 그 순간에만 유효하므로, 서버가 나중에 대신 승인하면 고객이 동의하지 않은 결제가 된다. 대사가 PG에 쓰기 요청을 보내는 경우는 돈을 돌려주는 방향(보상 취소 실패 건의 재취소)뿐이다. 4-5의 PG 재시도도 고객이 기다리는 그 요청 안으로 한정한다 | (사용자 작성) | phase-04 4-4 (2026-11-25) |
| 승인 대사 분류 (`PAYMENT_UNKNOWN`, 오래된 `PAYING`) | 조회 결과로 나눈다: `DONE` + 금액 일치 → `PAID`(재고 그대로) / `ABORTED`·`EXPIRED`·`READY`·`IN_PROGRESS` → `FAILED` + 재고 복구 (승인 요청을 다시 보내지 않으므로 승인될 길이 없다. 기다리면 재고와 "결제 확인 중" 화면만 묶인다) / `NOT_FOUND_PAYMENT` → 결제 쪽 장애로 보고 `FAILED` + 재고 복구 + `log.error` / 조회 실패(타임아웃·5xx·401) → 바꾸지 않고 다음 회차에 다시 조회. `DONE`인데 금액이 다르면 `PAID`로 확정하지 않고 다시 취소한다. 보상 취소 실패 건(`PAYMENT_CANCEL_FAILED`)도 같은 기준이다: 금액이 같으면(반영 실패) `PAID`, 다르면(금액 불일치) 다시 취소, 조회가 `CANCELED`면 `FAILED` | (사용자 작성) | phase-04 4-4 (2026-11-25) |
| 취소 대사·보상 취소 실패 건의 `NOT_FOUND_PAYMENT` | **(2026-11-26 변경) 취소 대사는 주문을 `CANCEL_REVIEW`로 옮겨 대사 대상에서 빼고 `log.error`를 남긴다.** 매 회차 같은 건을 다시 조회하지 않게 하고, 사람이 볼 건을 상태로 드러낸다. 원인을 고치면 수동 API로 `CANCEL_UNKNOWN`으로 되돌려 대사가 다시 확정한다. 보상 취소 실패 건(승인 쪽)은 이 결정과 달리 승인 대사가 `FAILED` + 재고 복구로 닫는다. 토스 장애 세 겹(다른 금액 승인, 보상 취소 무응답, 결제 기록 유실)이 겹쳐야 생기므로 고치지 않는다(알려진 한계). (이전) 상태를 바꾸지 않고 `log.error`만 남긴다. 이미 결제된 건이 조회되지 않는 것은 결제 결과가 아니라 시스템 장애(우리 쪽 설정·버그, 또는 토스 쪽 심각한 문제)이므로 **소스에서 처리할 문제가 아니다.** 사람이 확인하고, 원인을 고치면 다음 회차에 정상 확정된다. 회차 중단·조회 순서 강제는 넣지 않는다(신호가 우연에 달려 효과가 약함) | (사용자 작성) | phase-04 4-4 (2026-11-25) |
| 대사·만료의 상태 전이 방식 | **비관적 락으로 통일.** 대상 id를 락 없이 고른 뒤 주문마다 `FOR UPDATE` → 현재 상태 재확인 → 도메인 메서드로 전이. 조건부 UPDATE(원자적 쿼리)는 쓰지 않는다. 범위 조건 UPDATE는 갭 락으로 새 주문 INSERT를 막을 수 있고, 원자적 쿼리는 상태 규칙을 SQL에 한 번 더 두게 된다. 락 시간 차이는 "락 획득~커밋" 사이의 DB 왕복 정도라 뒷정리 작업에서는 의미가 없다 (phase-02 측정에서도 속도로 갈리지 않음, `docs/lock-comparison.md`). 결제 쪽 원자적 쿼리 구현은 이후 걷어낸다 | (사용자 작성) | phase-04 4-4 (2026-11-25) |
| 대사·만료 대상 조회 인덱스 | `orders (status, updated_at)` (V11). `(status)`만으로는 평소 상태(30분 지난 `PENDING`은 적고 최근 `PENDING`은 많음)에서 만료 쿼리가 `PENDING` 전체를 읽는다(3030행 1.04ms 대 30행 0.015ms). 쓰기 비용은 같다(`updated_at`은 상태 전이와 함께 바뀜). 측정: `docs/evidence/index-explain-reconciliation.md` | (사용자 작성) | phase-04 4-4 (2026-11-25) |
| 만료·대사 기준 시간 N | **대사:** `PAYMENT_UNKNOWN`·`PAYING`·`CANCEL_UNKNOWN`·`CANCELING` 모두 상태가 된 지(`updated_at`) 5분 지난 것부터 대상, 주기 1분. 짧게 잡았다가 틀리면 돈 사고(늦게 처리된 승인을 `FAILED`로 확정), 길게 잡았다가 틀리면 고객 대기이므로 넉넉하게 시작한다. **운영 근거 없는 초기값**이며 설정으로 빼고 회차 로그를 보고 줄인다. **만료:** 토스를 부르지 않고 DB만 바꾼다(`PENDING`은 PG 요청이 나간 적 없음). 주문 생성 후 30분 지난 `PENDING`이 대상, 주기 5분. 토스 결제 유효 30분을 위젯 표시부터로 본다(현실에서 위젯~결제 완료는 1분 안). 더 오래 머문 고객은 인증을 마쳐도 `ORDER_NOT_PAYABLE`을 받고 새 주문으로 결제한다(돈은 나가지 않음). **대사의 토스 조회 속도 제한:** 초당 5건 (토스 한도 미확인). 모든 값은 운영 근거 없는 초기값 | (사용자 작성) | phase-04 4-4 (2026-11-25) | (사용자 작성) | phase-04 4-4 (2026-11-25) |
| 대사·만료 운영 장치 | 묶음 500건씩 대상이 없을 때까지 반복, 한 회차 최대 50초. 토스 조회 3번 연속 실패 시 그 회차 중단. 스케줄러 스레드 2개. 회차 로그(대상·처리·조회 실패 건수, 남은 대상 여부, 소요 시간). 수동 호출 API 함께 둠. 보상 취소 실패 건을 다시 취소한 뒤 `failure_code=PAYMENT_AMOUNT_MISMATCH`. 값은 모두 운영 근거 없는 초기값(설정으로 뺌) | (사용자 작성) | phase-04 4-4 (2026-11-25) |
| 대사 구현 세부 (구현 중 Claude가 정함. 2026-11-26 사용자 확정: ①②④⑤. ③⑥은 정상 흐름에서 생기지 않는 방어 코드라 그대로 둠) | ① 대사로 `FAILED` 확정한 결제의 `failure_code`: 승인 안 됨·`NOT_FOUND_PAYMENT`는 새 코드 `PAYMENT_NOT_APPROVED`(409), 조회 `CANCELED`인 보상 취소 실패 건은 승인 금액으로 원래 사유를 되살림(금액 다름 → `PAYMENT_AMOUNT_MISMATCH`, 같음 → `INTERNAL_SERVER_ERROR`), 그 밖의 `CANCELED`는 `PAYMENT_NOT_APPROVED`. 같은 키 재요청에 이 코드가 돌아간다 ② 취소 대사로 무산된 취소 시도의 `failure_code`: 새 코드 `PAYMENT_CANCEL_NOT_APPLIED`(409) ③ 보상 취소 실패 건이 `PAID`로 확정되면 `failure_code`를 지움 ④ 조회 상태 `WAITING_FOR_DEPOSIT`·`PARTIAL_CANCELED`(이 서비스에서 생길 수 없음)는 바꾸지 않고 `log.error` ⑤ ~~토스 `404 NOT_FOUND`(`NOT_FOUND_PAYMENT`가 아닌 것)는 "결제 없음"으로 단정하지 않고 조회 실패로 봄~~ → **(2026-11-26 사용자 확정)** 조회 응답 분류: 5xx·429·일시 오류(`PROVIDER_ERROR`)·반복 요청 차단(`FORBIDDEN_CONSECUTIVE_REQUEST`)·타임아웃은 조회 실패(다음 회차, 연속 실패에 셈), 401·키 오류는 연동 오류(연속 실패에 셈), **그 밖의 4xx(일반 `404 NOT_FOUND`, 400 등)는 조회 거절**: 정상 흐름에서 생기지 않는 응답이라 그 건만 `log.error` 후 건너뛰고 연속 실패에 세지 않음 (다시 보내도 같은 응답이라, 세면 이런 건 몇 개가 매 회차 대사를 멈춤) ⑥ 금액 불일치 재취소가 실패하면 `PAYMENT_UNKNOWN` + `PAYMENT_CANCEL_FAILED`로 두고, 연속 실패 횟수에 조회 실패와 함께 셈 | (사용자 작성) | phase-04 4-4 (2026-11-26) |
| 승인 후 DB 반영 실패 | ~~보상 취소 → `FAILED`~~ → **보상 취소하지 않고 결과 모름(`PAYMENT_UNKNOWN` / `UNKNOWN`, `failure_code` 없음, `approved_amount` 기록)으로 두고 대사가 `PAID`로 확정.** 고객이 동의했고 PG도 서버 금액 그대로 승인해 돈이 나갔으며(카드사 승인 문자도 이미 감), 실패한 것은 우리 기록뿐이다. PG 호출 전 시도 기록과 대사가 생겨 되돌리지 않아도 수습할 수 있다. 대사의 "금액 같으면 `PAID`" 기준과 결론을 맞춘다. 금액 불일치는 그대로 보상 취소. 반영이 계속 실패하는 경우(버그 등)는 대사 반영 오류 로그로 사람이 본다 | (사용자 작성) 토스가 승인해 돈이 빠져나갔으므로 결제로 확정하는 게 맞다 | phase-04 4-4 (2026-11-26) |
| 대사 속도 제한의 단위 | 그대로 둔다: 속도 제한(`Pacer`)은 **대상 한 건마다** 기다린다. 금액 불일치 재취소는 한 건에 PG를 두 번(조회 + 취소) 부르므로 이런 건이 몰리면 PG 호출이 초당 5건을 넘을 수 있다(최대 약 10건). 금액 불일치는 정상 흐름에서 생기지 않는 건이다. 회차 처리량 측정: `docs/evidence/reconciliation-throughput.md` | (사용자 작성) | phase-04 4-4 (2026-11-26) |
| 가상계좌(입금 대기) 흐름 | **나중에 개발한다** (시점 미정). 지금 코드는 대사 조회에서 `WAITING_FOR_DEPOSIT`이 오면 바꾸지 않고 `log.error`만 남긴다(대사 구현 세부 ④) | (사용자 작성) | phase-04 4-4 (2026-11-26) |
| 승인 쪽 서킷 브레이커 | 토스 장애가 진행되는 동안 새 결제를 토스에 보내지 않고 바로 거절하는 것은 맞다(결과 모름이 계속 쌓이므로). 승인 흐름을 바꾸는 일이라 4-4 뒤에 별도로 다룬다 | (사용자 작성) | phase-04 4-4 (2026-11-25) |
| PG 호출 재시도 | 결과 모름(읽기 타임아웃·5xx·`IDEMPOTENT_REQUEST_PROCESSING`·`PROVIDER_ERROR`)일 때만 고객 요청 안에서 200ms 뒤 **같은 PG 키로 1번** 재시도. 거절·연결 실패·키 오류는 재시도하지 않음. 재시도도 결과 모름이면 지금처럼 `PAYMENT_UNKNOWN`/`CANCEL_UNKNOWN` → 대사. 고객 최대 대기 약 10초(읽기 타임아웃 5초 × 2). 긴 장애는 서킷 브레이커의 몫 (`docs/payment-pg-idempotency-discussion.md`) | (사용자 작성) | phase-04 4-5 (2026-11-26) |
| PG 멱등키 값 | 승인 `confirm-{orderNo}`(Order:Payment 1:1), 사용자 취소 `cancel-{paymentCancel.id}`(취소는 주문당 여러 번이라 시도마다 다름), 보상 취소는 호출마다 UUID(그 요청 안 재시도는 같은 값), 대사 재취소는 호출마다 새 UUID. 클라이언트 `Idempotency-Key`는 PG로 넘기지 않음. 헤더는 승인·취소(POST)에만 붙이고 조회에는 붙이지 않음. 토스는 키 없이도 이중 승인·이중 전액 취소를 막으므로, PG 키가 주는 것은 재시도 시 첫 결과를 다시 받는 것 | (사용자 작성) | phase-04 4-5 (2026-11-26) |
| 토스 클라이언트 자동 테스트 범위 | 응답 분류(성공·거절·5xx·이상한 본문)는 기존 `MockRestServiceServer` 테스트를 유지하고, WireMock(standalone, 테스트 의존성)으로는 실제 소켓이 필요한 네트워크 장애(연결 거부, 읽기 타임아웃, 연결 리셋, 응답 없이·본문 도중 끊김)와 재시도 시 PG 멱등키 헤더만 검증한다. 연결 타임아웃은 환경마다 흔들려 예외 주입으로 둔다 | (사용자 작성) | phase-04 4-6 (2026-11-27) |

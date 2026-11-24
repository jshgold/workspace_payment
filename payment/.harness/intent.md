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
| 취소 결과 모름 대사 | `CANCEL_UNKNOWN → CANCELED` / `CANCEL_UNKNOWN → PAID` |
| 서버 중단 추정 대사 | `CANCELING`(N분 경과) `→ CANCELED` / `→ PAID` |

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

- PG가 승인했지만 반영 단계에서 보상 취소한 경우(금액 불일치, DB 반영 실패)는 `FAILED` + `failure_code`로 남긴다.
- 보상 취소 자체가 실패하면 돈이 묶였는지 모르므로 `UNKNOWN`(주문 `PAYMENT_UNKNOWN`) + `failure_code=PAYMENT_CANCEL_FAILED`로 남겨 대사 대상으로 만든다. PG가 승인한 금액은 `approved_amount`에 남긴다. 대사는 승인 여부뿐 아니라 금액까지 비교해야 한다.
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
| 취소 멱등키 적용 | (4-3에서 결정) | | phase-04 |
| 만료·대사 기준 시간 N | (4-4에서 결정) | | phase-04 |

# Phase 04. 실 PG 테스트 연동

**목표:** 가짜 PG(`FakePaymentClient`)를 실제 PG의 테스트 환경으로 교체해, phase-03에서 만든 흐름(선점 → PG 승인 → 반영 → 보상)이 진짜 외부 API 위에서도 맞게 동작하는지 확인한다.
가짜 PG는 우리가 정한 시나리오만 만들지만, 실제 PG는 응답 형식·에러 코드·지연이 우리 가정과 다를 수 있다.

**시작 전에 정할 것**
- PG사: 토스페이먼츠 (권장. 서버가 승인(confirm)을 호출하는 현재 구조와 가장 가깝다)
- 결제 UI(client)는 만들지 않는다. 테스트용 결제창에서 `paymentKey`를 받는 정도의 최소 수단만 쓴다.
- 실제 키는 코드와 git에 넣지 않는다. (환경변수 또는 gitignore된 설정 파일)

**원칙**
- PG API의 요청·응답 형식은 구현 전에 공식 문서로 확인한다. 기억에 의존하지 않는다.
- 자동 테스트(CI)는 실제 PG를 호출하지 않는다. 실제 PG 호출은 수동 확인으로만 한다.
- `PaymentFacade`의 오케스트레이션은 4-2+에서 정한 순서(시도 기록·선점 → PG → 결과 반영 → 보상)를 따른다. 그 밖의 변경이 필요하면 이유를 먼저 설명한다.

---

- [x] **4-1. PG 선정과 테스트 환경 준비**
  - PG 가입, 테스트 키 발급, 공식 문서에서 승인·취소·조회 API 확인
  - 키를 `@ConfigurationProperties`로 주입하고, 프로필(`local`/`test`)로 `FakePaymentClient`와 실제 클라이언트를 전환할 수 있게 설정
  - **완료 조건:** 키가 저장소에 남지 않고, 프로필에 따라 어떤 `PaymentClient`가 주입되는지 확인된다.
  - **학습 포인트:** 시크릿을 코드에서 분리하지 않으면 어떤 사고가 나는가?

- [x] **4-2. 승인 연동 (`approve`)**
  - 승인 요청에 PG 결제 식별자(`paymentKey` 등)를 받도록 API와 `PgApproveRequest` 확장 (3단계 제약조건 포함)
  - 실제 클라이언트 구현체 작성, 연결·읽기 타임아웃 명시
  - PG 응답을 우리 분류로 매핑: **명확한 거절**(`PaymentDeclinedException`) vs **결과를 모름**(타임아웃·5xx)
  - **완료 조건:** 테스트 환경에서 정상 승인, 거절(테스트 카드/금액), 승인 금액 불일치가 의도대로 처리된다.
  - **학습 포인트:** 4xx와 5xx/타임아웃을 왜 다르게 다뤄야 하나? 잘못 분류하면 PG에서 승인된 결제를 실패로 처리하게 된다.
  - **후속:** 이 시점의 구현은 타임아웃·5xx 등 결과를 모르는 경우 `PAYING`을 유지하고, 거절(`PaymentDeclinedException`)과 연동 오류(`PaymentGatewayException`)는 `PAYING → PENDING`으로 되돌린다. 어느 경우든 Payment는 승인 성공 시에만 생성된다. 4-2+에서 재설계한다.

- [ ] **4-2+. 결제 모델 재설계 (Payment = 결제 시도 기록)** — 설계 검토 중 발견한 결함 수정 (`docs/payment-redesign-discussion.md`)
  - **배경:** phase-03 3-3 명세대로 Payment를 승인 후에만 생성한다. 타임아웃은 `PAYING`으로 막혀 이중 결제는 일어나지 않지만, 아래 문제가 남아 있다.
    - 결과를 모르는 건(타임아웃)에 결제 기록이 없다. 주문 `PAYING`과 멱등키 `IN_PROGRESS`만 남고, 무엇을 시도했는지(`paymentKey`, 금액)는 결제 쪽에 남지 않는다.
    - 거절된 결제의 이력이 사라진다. 주문이 `PENDING`으로 돌아가고 Payment row도 없다.
    - 거절 후 같은 주문으로 재시도하면 같은 orderId가 PG에 다시 전달된다. (PG가 이를 허용하는지는 공식 문서로 확인한다)
    - `PAYING`이 "진행 중"과 "결과 모름"을 겸해, 대사 대상을 시간으로 추측해야 한다.
  - **시작 전 (구현 전에 먼저 할 것):**
    1. 현재 하네스가 커밋되어 있는지 확인하고, 새 하네스는 별도 커밋으로 넣는다. (명세 변경 diff를 남기기 위해)
    2. 현재 커밋에 git tag를 남긴다. (예: `before-payment-redesign`)
    3. 문제를 재현하는 테스트를 먼저 작성하고 **실패하는 것을 확인한다.** 테스트는 바뀐 뒤의 올바른 동작을 기대하도록 쓴다. 실패 출력은 `docs/evidence/before-redesign.md`에 저장한다.
       - PG가 승인을 거절 → Payment row가 `FAILED`(+`failure_code`)로 남아야 한다 (현재: row 없음)
       - PG가 승인을 거절 → 주문이 `FAILED`로 끝나 같은 주문으로 재결제할 수 없어야 한다 (현재: `PENDING`으로 복구)
       - PG 응답 타임아웃 → Payment row가 `paymentKey`, 금액과 함께 남아야 한다 (현재: row 없음)
       - PG 응답 타임아웃 → 주문이 `PAYMENT_UNKNOWN`이어야 한다 (현재: `PAYING`)
    4. 구현 계획(상태 전이표, V9 마이그레이션, 변경 클래스/테스트 목록)을 보고하고 확인받는다.
  - **구현:**
    - **먼저 확인:** 현재 `pg_transaction_id`에 저장하는 값이 요청으로 받은 `paymentKey`인지 확인한다. `paymentKey`라면 컬럼을 새로 추가하지 않고 기존 컬럼을 쓰거나 `payment_key`로 이름만 바꾸며, `REQUESTED` 생성 시점에 항상 채울 수 있으므로 NOT NULL을 유지한다.
    - Flyway `V9`: `payment`에 `status`, `idempotency_key`(UNIQUE), `failure_code` 추가(위 확인 결과에 따라 `payment_key` 처리), `orders.status`에 `PAYMENT_UNKNOWN`, `FAILED`, `EXPIRED`, `CANCEL_UNKNOWN` 추가. 기존 `payment` 행은 `APPROVED`로, `idempotency_key` 테이블 데이터는 `payment.idempotency_key`로 이관 (V4/V5는 수정하지 않는다)
    - `startPaying` 트랜잭션에서 `Order=PAYING` 선점, 재고 차감(개선 A, 유지), `Payment=REQUESTED` INSERT를 함께 한다. 재고가 모자라면 셋 다 롤백되어 PG를 호출하지 않는다.
    - 결과 반영 (모두 `WHERE status = 'PAYING'` 조건부 전이): 승인 → `APPROVED`/`PAID`, 확정 실패 → `FAILED`/`FAILED`(+`failure_code`), 결과 모름 → `UNKNOWN`/`PAYMENT_UNKNOWN`
    - `PaymentGatewayException`을 분류한다: 요청이 PG에 도달하지 않았음이 확실한 경우(연결 실패, 요청 생성 실패, 인증 오류)는 `FAILED`, 읽기 타임아웃·5xx는 결과 모름
    - 같은 멱등키 재요청 (락 없이 키로 먼저 조회): `APPROVED` → 첫 결과, `FAILED` → 같은 실패, `REQUESTED`/`UNKNOWN` → `PAYMENT_IN_PROGRESS`. 같은 키로 다른 주문/금액이 오면 거절
    - 키 조회에 없으면 주문 락 획득 후 상태 재확인: `PENDING`이 아니면 `PAYMENT_IN_PROGRESS`(409) 반환. 프론트는 이 코드를 에러로 표시하지 않는다
    - 재고 복구 위치 이동: 지금 `revertToPending`에서 하는 재고 복구를 `→ FAILED` 전이(확정 실패, 보상 취소 성공 후)로 옮긴다. `→ PAID`, `→ PAYMENT_UNKNOWN` 전이는 재고를 건드리지 않는다.
    - `PAYING → PENDING` 간선 제거, `IdempotencyService`와 키 엔티티/테이블 정리
    - 비관적 락 / Atomic Update 두 `PaymentStateService` 구현체 모두 반영
  - **완료 조건:**
    - 시작 전에 작성한 재현 테스트가 통과한다.
    - PG 응답 타임아웃 시 주문은 `PAYMENT_UNKNOWN`, Payment는 `UNKNOWN`이 되고, 같은 주문으로 재결제가 막힌다.
    - 같은 주문에 동시 승인 요청 10건 → PG 호출 1번, 성공 1건 (phase-03 3-4 회귀)
    - 기존 락 전략 결제 흐름·승인 테스트가 회귀 통과한다. (전체 테스트가 필요하면 TEST_AND_CI에 따라 먼저 묻는다)
    - `intent.md` 8절 결정 기록이 갱신되어 있다.
  - **학습 포인트:** 왜 Payment를 "승인 결과"로 모델링하면 결과를 모르는 상태를 표현할 수 없나? 락/상태 선점이 있는데도 멱등키가 필요한 이유는 무엇인가? (이중 처리 방어 vs replay)

- [ ] **4-3. 취소 연동 (`cancel`)**
  - 보상 취소와 사용자 결제 취소 모두 실제 PG 취소 API를 호출
  - 이미 취소된 건에 대한 PG 응답을 어떻게 볼지 정한다. (실패인가, 성공으로 간주할 것인가)
  - **취소에도 멱등키를 적용할지 정한다.** 승인과 같은 replay 문제(응답 유실 시 취소 여부를 모름)가 있으므로, 적용하지 않는다면 그 이유를 결정 기록에 남긴다. 적용한다면 승인과 같은 구조(취소 시도 기록 → PG → 반영)를 따른다.
  - 취소 결과도 성공 / 명확한 거절 / 결과 모름으로 분류한다. 결과 모름이면 `CANCEL_UNKNOWN`으로 전이하고 대사로 확정한다.
  - **완료 조건:** 승인 후 취소하면 PG 쪽 상태도 취소로 바뀌고, 우리 Order는 `CANCELED`, Payment는 `CANCELED`, 재고가 복구된다. 취소 타임아웃 시 `CANCEL_UNKNOWN`이 된다.
  - **학습 포인트:** 취소 요청이 타임아웃 났을 때 PG에서는 취소됐을 수도 있다. 이때 우리 상태는 어떻게 두어야 하나?

- [ ] **4-4. 결제 조회, 대사, 만료**
  - 대사와 만료의 기준 시간 N을 정하고 결정 기록에 남긴다.
  - PG 결제 조회 API 연동 (`paymentKey` 또는 주문 식별자 기준)
  - 결제 대사 (스케줄러 또는 수동 호출 API). 대상: `PAYMENT_UNKNOWN` 전체 + N분 넘게 `PAYING`에 머문 주문(서버 중단 추정)
    - PG가 승인한 건: `PAID`로 확정하고 재고를 차감한다. 재고가 부족하면 보상 취소 후 `FAILED`
    - PG가 승인하지 않은 건: `FAILED`로 종료
  - 취소 대사. 대상: `CANCEL_UNKNOWN` 전체 + N분 넘게 `CANCELING`에 머문 주문 → `CANCELED`(재고 복구) 또는 `PAID`
  - 주문 만료: N분 넘게 `PENDING`인 주문을 `EXPIRED`로 바꾼다. 결제 시작과 겹쳐도 한쪽만 반영되도록 `WHERE status = 'PENDING'` 조건부 전이로 한다.
  - 대사·만료 대상 조회 쿼리의 실행 계획을 확인하고 필요한 인덱스를 정한다.
  - **완료 조건:**
    - `PAYMENT_UNKNOWN`과 오래된 `PAYING` 주문이 PG가 승인한 건이면 `PAID`로, 승인되지 않은 건이면 `FAILED`로 종료된다.
    - 대사와 일반 승인 요청(또는 늦게 도착한 응답 반영)이 동시에 들어와도 한 번만 반영된다.
    - 만료와 결제 시작이 동시에 일어나도 한쪽만 반영된다.
  - **학습 포인트:** phase-03 마무리 질문("PG 승인은 됐는데 서버가 죽었다")의 답. 대사 작업은 어떤 락/상태 전이로 중복 반영을 막나?

- [ ] **4-5. PG 호출 멱등성**
  - PG가 지원하는 멱등 키 헤더에 우리 주문 기준 값을 전달해, 재시도해도 PG에서 이중 승인/이중 취소되지 않게 한다.
  - **완료 조건:** 같은 주문의 승인 요청을 재시도해도 PG 승인이 한 번만 일어난다는 것을 확인한다.
  - **학습 포인트:** 우리 쪽 `Idempotency-Key`(client → 서버)와 PG 쪽 멱등 키(서버 → PG)는 무엇이 다르고, 각각 어떤 중복을 막나?

- [ ] **4-6. 테스트 전략과 수동 E2E**
  - 자동 테스트: 가짜 HTTP 서버(예: WireMock)로 PG 응답(성공/거절/타임아웃/5xx/연결 실패)을 흉내 내 실제 클라이언트 구현체를 검증
  - 수동 E2E: 상품 등록 → 주문 → 테스트 결제창에서 `paymentKey` 획득 → 승인 → 취소 → 재고 확인
  - **완료 조건:** 자동 테스트가 실제 PG 없이 통과하고, 수동 E2E 결과(요청/응답/상태 변화)가 기록된다.
  - **학습 포인트:** 외부 API를 쓰는 코드의 테스트를 어디까지 자동화하고, 어디서부터 수동으로 확인해야 하나?

---

**Phase 완료 후 스스로 답해볼 것**
- 가짜 PG에서는 통과했는데 실제 PG에서 처음 깨진 가정은 무엇이었나?
- phase-03 명세(Payment를 승인 후 저장)는 왜 결함이었고, 명세 단계에서 무엇을 물었다면 미리 막을 수 있었나?
- 이제 Kafka/Outbox를 도입한다면, 결제 완료 이벤트와 보상 취소 재시도 중 어느 쪽이 먼저 필요한가?

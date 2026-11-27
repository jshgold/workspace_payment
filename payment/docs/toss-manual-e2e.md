# 토스 수동 E2E 시나리오 (phase-04 4-6)

실제 토스 테스트 환경으로 승인 → 취소 흐름을 한 번 끝까지 돌려 보고, 가짜 PG로는 확인할 수 없던 것을 확인한다.
자동 테스트(CI)는 실제 토스를 부르지 않는다. 이 문서의 절차는 사람이 직접 실행한다.

## 0. 이 문서로 확인할 것

| # | 시나리오 | 확인할 것 | 미뤄 둔 곳 |
|---|---|---|---|
| E1 | 정상 승인 → 취소 | 주문 `PAID` → `CANCELED`, Payment `APPROVED` → `CANCELED`, 재고 5 → 4 → 5, 토스 개발자센터의 결제 내역도 취소로 바뀌었는지 | 4-3 |
| E2 | 같은 PG 멱등키로 승인 재전송 | 토스가 처리하지 않고 첫 응답(200, `DONE`)을 그대로 돌려주는지. 키 없이 보내면 `ALREADY_PROCESSED_PAYMENT`가 오는지 | 4-5 |
| E3 | 같은 PG 멱등키로 취소 재전송 | 첫 응답(200, `CANCELED`)을 그대로 돌려주는지. 다른 키로 보내면 `ALREADY_CANCELED_PAYMENT`가 오는지 | 4-5 |
| E4 | 결제 조회 | 조회 응답의 `status`, `totalAmount`가 우리 파싱(`TossLookupPayment`)과 맞는지 (대사가 쓰는 API) | 4-4 |
| E5 | 토스가 승인을 거절 | 결제창 금액과 승인 금액이 다를 때 토스가 거절하고, 우리 주문 `FAILED`, Payment `FAILED`, 재고 복구되는지 | 4-2 |

**확인하지 못하는 것:** 토스가 5xx 응답도 멱등키에 저장하는가(4-5). 실제 토스에서 5xx를 원할 때 낼 수 없으므로 "미확인"으로 남긴다.

**실행 순서:** E1 승인 → **E2** → E1 취소 → **E3** → **E4** → **E5**.
E2는 취소 전에 해야 "승인된 결제"에 대한 재전송이 되므로 E1 사이에 끼운다.

**결제 금액 주의:** 이전 테스트에서 카카오페이 결제를 고액(2억원·3천만원)으로 요청했다가 카카오페이가 차단한 적이 있다. 결제창 금액은 1000원 근처로 둔다. (토스 API 호출 수 때문에 막힌 것은 아니다)

## 1. 준비

### 1-1. 역할

| 누가 | 하는 일 |
|---|---|
| 사용자 | 서버 실행(시크릿 키 주입), 결제창에서 테스트 카드 결제, 토스에 직접 보내는 curl(E2~E4) 실행, 토스 개발자센터 확인 |
| Claude | 우리 서버 API 호출(상품·주문·승인·취소), DB 상태 확인, 결과 기록 |

시크릿 키(`test_sk_...`)는 Claude가 읽지 않는다. 직접 보내는 curl은 사용자 터미널에서 `$TOSS_SECRET_KEY`로 실행한다.

### 1-2. 실행

```bash
# 1) DB
docker compose up -d mysql

# 2) 서버 (local + toss 프로필). bootRun은 .env의 TOSS_SECRET_KEY를 읽는다
./gradlew bootRun --args='--spring.profiles.active=local,toss'

# 3) 결제창 페이지 (별도 터미널, 프로젝트 상위의 front_payment 폴더)
cd ../front_payment && python3 -m http.server 8000
# 브라우저: http://localhost:8000/toss-test-checkout.html
```

**확인:** 서버 기동 로그에 `TossPaymentClient`가 주입됐는지 본다. (`FakePaymentClient`면 프로필이 잘못된 것)

### 1-3. 직접 호출용 셸 변수 (사용자 터미널)

```bash
# 시크릿 키는 화면에 찍지 않는다. .env에서 읽어 이 셸에만 둔다
export TOSS_SECRET_KEY=$(grep '^TOSS_SECRET_KEY=' .env | cut -d= -f2- | tr -d '"')
AUTH="Authorization: Basic $(printf '%s:' "$TOSS_SECRET_KEY" | base64)"
```

### 1-4. DB 확인 쿼리

```bash
docker exec -it payment-mysql mysql -u payment -p payment_p
```

```sql
-- {ORDER_ID}는 주문 PK
SELECT o.id, o.order_no, o.status AS order_status, o.updated_at,
       p.status AS payment_status, p.failure_code, p.approved_amount, p.payment_key
FROM orders o LEFT JOIN payment p ON p.order_id = o.id
WHERE o.id = {ORDER_ID};

SELECT id, status, failure_code, reason FROM payment_cancel WHERE order_id = {ORDER_ID};

SELECT id, stock, status FROM product WHERE id = {PRODUCT_ID};
```

## 2. E1. 정상 승인 → 취소 (상품 1 × 재고 5, 주문 수량 1)

### E1-1. 상품 등록

```bash
curl -s -X POST http://localhost:8080/api/products \
  -H "Content-Type: application/json" \
  -d '{"name":"E2E 테스트 상품","price":1000,"stock":5}'
```
- 기대: 201, `stock=5`, `status=ON_SALE`
- 기록: `PRODUCT_ID`

### E1-2. 주문 생성

```bash
curl -s -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{"memberId":1,"productId":{PRODUCT_ID},"quantity":1}'
```
- 기대: 201, `status=PENDING`, `totalPrice=1000`
- 기록: `ORDER_ID`(id), `ORDER_NO`(orderNo, 36자 UUID)
- **30분 안에 결제를 시작한다.** 지나면 만료 작업이 `EXPIRED`로 바꾼다.

### E1-3. 결제창에서 결제 (사용자)

1. 결제창 페이지에 클라이언트 키(`test_ck_...`), 주문번호 `ORDER_NO`, 금액 `1000`을 넣고 "카드 결제창 열기"
2. 테스트 카드로 결제 완료
3. 돌아온 화면에서 `paymentKey`를 복사한다. (페이지가 승인 curl도 만들어 준다)
- 기록: `PAYMENT_KEY`
- 이 시점에 토스 쪽 결제는 아직 승인 전(인증만 끝남)이다. 승인은 우리 서버가 한다.

### E1-4. 승인 (우리 서버 → 토스 confirm)

```bash
curl -s -X POST http://localhost:8080/api/orders/{ORDER_ID}/payments/approve \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: e2e-approve-1" \
  -d '{"amount":1000,"paymentKey":"{PAYMENT_KEY}"}'
```
- 기대 응답: 200, `orderStatus=PAID`, `paymentStatus=APPROVED`, `amount=1000`
- 기대 DB: 주문 `PAID`, Payment `APPROVED`, `approved_amount=1000`, `failure_code` 없음, 재고 **4**
- 기대 로그: 토스 confirm 호출 1번, 재시도 로그(`결과 모름. ...ms 뒤 같은 PG 멱등키로`) 없음

→ **여기서 E2를 한다.** (취소 전)

### E1-5. 취소 (우리 서버 → 토스 cancel)

```bash
curl -s -X POST http://localhost:8080/api/orders/{ORDER_ID}/payments/cancel \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: e2e-cancel-1" \
  -d '{"reason":"E2E 취소 확인"}'
```
- 기대 응답: 200, `orderStatus=CANCELED`, `paymentStatus=CANCELED`
- 기대 DB: 주문 `CANCELED`, Payment `CANCELED`, `payment_cancel` 1행 `COMPLETED`, 재고 **5**
- 기록: `CANCEL_ID`(`payment_cancel.id`). 토스에 보낸 PG 멱등키는 `cancel-{CANCEL_ID}`다.

### E1-6. 토스 개발자센터 확인 (사용자)

- 개발자센터 → 테스트 결제 내역에서 `ORDER_NO` 건의 상태가 **취소**인지 본다.

## 3. E2. 같은 PG 멱등키로 승인 재전송 (E1-4 직후, 취소 전)

우리 서버가 보낸 것과 **같은 키**(`confirm-{ORDER_NO}`)로 토스에 직접 다시 보낸다. 우리 서버의 `PgRetry`가 응답 유실 뒤 하는 재시도와 같은 요청이다.

> **값 주의 (2026-11-27 실제로 틀림)**
> - 본문 `orderId`에는 DB id(`ORDER_ID`, 예: `14795`)가 아니라 **`ORDER_NO`(UUID)**를 넣는다. 토스는 결제창에서 받은 주문번호만 안다.
> - 키는 E1-4 때 우리 서버에 보낸 클라이언트 키(`Idempotency-Key`)가 아니라 **`confirm-` + `ORDER_NO`(UUID)**다. 클라이언트 키는 토스로 넘기지 않는다(intent 8절 "PG 멱등키 값"). `confirm-{ORDER_ID}`도 아니다.
> - **실패한 요청에 쓴 키는 다시 쓰지 않는다.** 토스는 4xx 응답도 키에 저장해 두고, 같은 키로 오면 본문을 고쳐도 그 4xx를 그대로 돌려준다.

### E2-1. 같은 키 (사용자 터미널)

```bash
curl -s -i -X POST https://api.tosspayments.com/v1/payments/confirm \
  -H "$AUTH" -H "Content-Type: application/json" \
  -H "Idempotency-Key: confirm-{ORDER_NO}" \
  -d '{"paymentKey":"{PAYMENT_KEY}","orderId":"{ORDER_NO}","amount":1000}'
```
- 기대: 200, `status=DONE`. 응답의 `approvedAt`이 `server.log`의 E1-4 승인 반영 시각(`[결제 완료 알림]` 로그)과 같으면 토스가 새로 처리하지 않고 첫 응답을 돌려준 것이다. (우리 approve 응답 `PaymentResponse`에는 `approvedAt`이 없어 그것과는 비교할 수 없다)
- 함께 판정: 바로 다음 E2-2(키 없음)가 `ALREADY_PROCESSED_PAYMENT`를 받으면, 같은 본문인데 키에 따라 응답이 갈린 것이므로 이 200은 replay다.
- 기대: 개발자센터에 승인이 한 건뿐이다.

### E2-2. 키 없이 (몇 초 뒤)

```bash
curl -s -i -X POST https://api.tosspayments.com/v1/payments/confirm \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"paymentKey":"{PAYMENT_KEY}","orderId":"{ORDER_NO}","amount":1000}'
```
- 기대: 4xx, `code=ALREADY_PROCESSED_PAYMENT`. 우리 클라이언트는 이 코드를 **결과 모름**으로 분류한다(거절로 확정하지 않음). 그 분류가 맞는 응답인지 확인한다.
- 우리 DB는 바뀌지 않아야 한다. (직접 호출이라 우리 서버를 거치지 않음)

→ E1-5(취소)로 돌아간다.

## 4. E3. 같은 PG 멱등키로 취소 재전송 (E1-5 직후)

> **값 주의:** 키는 `cancel-` + `payment_cancel.id`(`CANCEL_ID`)다. E1-5 때 우리 서버에 보낸 클라이언트 키가 아니다. `PAYMENT_KEY`는 취소한 주문(E1)의 것을 쓴다.

### E3-1. 같은 키 (사용자 터미널)

```bash
curl -s -i -X POST https://api.tosspayments.com/v1/payments/{PAYMENT_KEY}/cancel \
  -H "$AUTH" -H "Content-Type: application/json" \
  -H "Idempotency-Key: cancel-{CANCEL_ID}" \
  -d '{"cancelReason":"E2E 취소 확인"}'
```
- 기대: 200, `status=CANCELED`. E1-5 때와 같은 취소 내역(`cancels[0].canceledAt` 등)이 오면 첫 응답을 돌려준 것이다.

### E3-2. 다른 키 (몇 초 뒤)

```bash
curl -s -i -X POST https://api.tosspayments.com/v1/payments/{PAYMENT_KEY}/cancel \
  -H "$AUTH" -H "Content-Type: application/json" \
  -H "Idempotency-Key: e2e-other-cancel-key" \
  -d '{"cancelReason":"E2E 취소 확인"}'
```
- 기대: 4xx, `code=ALREADY_CANCELED_PAYMENT`. 우리 클라이언트는 이 코드를 **취소 성공**으로 본다(intent 8절). 응답 코드가 문서와 같은지 확인한다.

## 5. E4. 결제 조회 (E3 뒤)

```bash
curl -s -i https://api.tosspayments.com/v1/payments/{PAYMENT_KEY} -H "$AUTH"
```
- 기대: 200, `status=CANCELED`, `totalAmount=1000`. 대사가 읽는 필드는 `status`, `totalAmount` 두 개다.
- 함께 볼 것: 전액 취소 뒤에도 `totalAmount`가 1000 그대로인지(`balanceAmount`만 0이 되는지). 대사의 금액 비교가 `totalAmount`를 쓰기 때문이다.

## 6. E5. 토스가 승인을 거절 (새 주문, 결제창 금액 ≠ 승인 금액)

결제창은 900원으로 열고, 우리 서버는 주문 금액 1000원으로 승인을 요청한다. 우리 서버의 요청 금액 검증(`PAYMENT_REQUEST_AMOUNT_MISMATCH`)은 통과하고, 토스가 금액이 다르다고 거절하는 경우다.

### E5-1. 주문 생성

E1-2와 같다. 기록: `ORDER_ID_2`, `ORDER_NO_2`. 재고는 시작 시점 값을 기록해 둔다(E2용 `PAID` 주문이 남아 있으면 5가 아니다).

### E5-2. 결제창에서 **900원**으로 결제 (사용자)

주문번호 `ORDER_NO_2`, 금액 `900`. 기록: `PAYMENT_KEY_2`

### E5-3. 승인 (요청 금액은 주문 금액 1000)

```bash
curl -s -X POST http://localhost:8080/api/orders/{ORDER_ID_2}/payments/approve \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: e2e-approve-2" \
  -d '{"amount":1000,"paymentKey":"{PAYMENT_KEY_2}"}'
```
- 기대 응답: 4xx 거절 (`PaymentDeclinedException` 응답)
- 기대 DB: 주문 `FAILED`, Payment `FAILED` + `failure_code`, 재고는 시작 값 그대로 (차감했다가 복구)
- 결제창 금액은 1000이 아니기만 하면 된다(900, 3000 모두 가능). 승인 요청의 `amount`는 반드시 1000이다. 여기를 바꾸면 토스까지 가지 않고 우리 서버가 `PAYMENT_REQUEST_AMOUNT_MISMATCH`로 먼저 거절한다.
- 기록: 토스가 준 상태 코드와 `code`. 2026-11-28 실측은 403 `FORBIDDEN_REQUEST`. 우리 클라이언트는 이 코드를 `CONFIG_ERROR_CODES`(`UNAUTHORIZED_KEY`, `INVALID_API_KEY`)에 넣지 않으므로 일반 4xx, 즉 **명확한 거절**(`PaymentDeclinedException`, `PAYMENT_001`)로 분류한다.
- 다른 코드가 오면 그 코드를 우리가 어떻게 분류하는지(거절 / 연동 오류 / 결과 모름) 확인한다.

## 7. 결과 기록

실행하면서 채운다. 응답 본문은 필요한 필드만 남기고, 시크릿 키나 `Authorization` 헤더는 남기지 않는다.

**실행 일시:** 2026-11-27 20:38 (상품 등록) ~ 2026-11-28 00:04 (E5)
**E2용 두 번째 승인:** ORDER_ID=14794 / ORDER_NO=01a21cee-2425-7008-9f09-a62b7f73bceb / PAYMENT_KEY=tviva261127oLXku3kY3PvPq (21:18:54 승인, paymentId=14793)
**E2용 세 번째 승인 (E2 실제 수행):** ORDER_ID=14795 / ORDER_NO=01a21d67-a668-7d3f-b353-c1c40954f104 / PAYMENT_KEY=tviva261127cBCS6Ydj1Ko81 (23:36:44 승인, paymentId=14794). 취소하지 않고 `PAID`로 둠
**ID:** PRODUCT_ID=2110 / ORDER_ID=14792 / ORDER_NO=01a21cd2-a26d-70ab-8fc6-dad3b1edc7a6 / PAYMENT_KEY=tviva261127n4ktw48q8pYpp / CANCEL_ID=2 / ORDER_ID_2=14796 / ORDER_NO_2=01a21d86-ed23-7a23-bd59-1339f5ebbe09 / PAYMENT_KEY_2=tviva2611284JPgwwsfgUVpN

| 단계 | 기대 | 실제 (응답 코드, 주요 필드) | DB 상태 (주문 / Payment / 재고) | 결과 |
|---|---|---|---|---|
| E1-1 상품 등록 | 201, stock=5 | `id=2110`, `stock=5`, `status=ON_SALE` (20:38:22) | 재고 5 | 통과 |
| E1-2 주문 | 201, PENDING | `id=14792`, `status=PENDING`, `totalPrice=1000` (20:44:55) | PENDING / 없음 / 5 | 통과 |
| E1-4 승인 | 200, PAID / APPROVED | `orderStatus=PAID`, `paymentStatus=APPROVED`, `amount=1000` (Idempotency-Key는 결제창 페이지가 만든 UUID). 재시도·WARN 로그 없음 | 취소 뒤 확인: `approved_amount=1000`, `failure_code` 없음 | 통과 |
| E2-1 같은 키 승인 (주문 14795) | 200, DONE, 첫 응답과 같음 | 2026-11-27 23:48:28. 키 `confirm-01a21d67-...` → **200 `DONE`**, `approvedAt=23:36:44` = 서버 로그의 E1-4 승인 반영 시각(23:36:44.871). 새 승인 아님 | 변화 없음 | 통과 |
| E2-2 키 없이 승인 (주문 14795) | 4xx ALREADY_PROCESSED_PAYMENT | 23:45:29. **400** `ALREADY_PROCESSED_PAYMENT` (409 아님). 우리 클라이언트는 코드로 판정(`UNKNOWN_RESULT_CODES`)하므로 결과 모름으로 분류 | 변화 없음 | 통과 |
| E1-5 취소 | 200, CANCELED / CANCELED | `orderStatus=CANCELED`, `paymentStatus=CANCELED`, `amount=1000`. 20:53:27 요청 → 20:53:29 반영, `ALREADY_CANCELED` 경고 없음 | CANCELED / CANCELED / 5, `payment_cancel` id=2 `COMPLETED` | 통과 |
| E1-6 개발자센터 | 취소 | 취소 (사용자 확인) | | 통과 |
| E3-1 같은 키 취소 (주문 14792) | 200, CANCELED, 첫 응답과 같음 | 2026-11-27 23:54:01. 키 `cancel-2` → **200 `CANCELED`**, `cancels` 1건, `canceledAt=20:53:28`(E1-5 요청 20:53:27 ~ 반영 20:53:29 사이), `transactionKey` = `lastTransactionKey`. 새 취소 거래 없음 | 변화 없음 | 통과 |
| E3-2 다른 키 취소 (주문 14792) | 4xx ALREADY_CANCELED_PAYMENT | 23:55:07. 키 `cancel-21241234` → **400** `ALREADY_CANCELED_PAYMENT`. 우리 클라이언트는 상태 코드보다 먼저 코드를 보고(`ALREADY_CANCELED_CODES`) 취소 성공으로 분류 | 변화 없음 | 통과 |
| E4 조회 (주문 14792) | 200, CANCELED, totalAmount=1000 | 23:56:12. 200, `status=CANCELED`, `totalAmount=1000`, `balanceAmount=0`. `TossLookupPayment(paymentKey, status, totalAmount)` 세 필드 모두 일치. 응답 헤더 `x-ratelimit-max-limit-per-minute: 200`, `x-ratelimit-remaining: 200` | | 통과 |
| E5-3 금액 다른 승인 (주문 14796, 결제창 3000원 / 승인 1000원) | 4xx 거절 | 2026-11-28 00:04:27. 토스 **403** `FORBIDDEN_REQUEST`("허용되지 않은 요청입니다.") → `PaymentDeclinedException` → 우리 응답 `PAYMENT_001`(402) | FAILED / FAILED(`PAYMENT_DECLINED`) / 4 (3으로 차감 후 복구) | 통과 |
| 5xx 멱등키 저장 | 확인 불가 | 미확인 (실제 토스에서 5xx를 낼 수 없음). 4xx는 저장됨 (아래 관찰 2) | | 미확인 |
| 추가: 만료 (주문 14793, 21:14:42 생성) | 30~35분 뒤 EXPIRED, Payment 없음, 재고 그대로 | 21:48:28 만료 회차 `대상=1, 만료=1, 오류=0` | | 통과 |
| 추가: 우리 서버에 같은 키로 승인 재전송 (주문 14794) | 200, 첫 결과 replay, 토스 호출 없음 | 21:19:05, 21:19:43 두 번 200. 두 요청 모두 SELECT만 하고 쓰기 없음 | 변화 없음 (PAID / APPROVED / 4) | 통과 |
| 추가: 키 없이 승인 (주문 14794) | 400 | `COMMON_002` (`MissingRequestHeaderException`), 21:20 두 번 | 변화 없음 (PAID / APPROVED / 4) | 통과 |

**가짜 PG와 다르게 동작한 것:** (phase 완료 질문 "가짜 PG에서는 통과했는데 실제 PG에서 처음 깨진 가정은 무엇이었나?"의 답이 된다)
- "이미 처리됨" 응답(`ALREADY_PROCESSED_PAYMENT`, `ALREADY_CANCELED_PAYMENT`)이 409가 아니라 **400**으로 왔다. 상태 코드만으로 분류했다면 400을 명확한 거절로 보고, 이미 승인된 결제를 `FAILED`로 닫거나 이미 취소된 건을 `PAID`로 되돌렸을 것이다. 우리 클라이언트는 에러 코드를 먼저 보므로 맞게 분류됐다. (`TossPaymentClient` 클래스 주석의 "`ALREADY_PROCESSED_PAYMENT`, 409" 표기를 실측에 맞춰 고침)
- 토스는 금액 불일치를 전용 코드 없이 403 `FORBIDDEN_REQUEST`로 알린다. 결제창 인증 내용과 승인 요청이 다를 때(금액, `orderId`) 같은 코드가 왔다.

**E2 진행 중 토스에 보낸 confirm 요청 (주문 14795, 모두 같은 `paymentKey`)**

| # | 시각 | 키 | 본문 `orderId` | 응답 | 해석 |
|---|---|---|---|---|---|
| 1 | 23:36:54 | `98ad220c-...` (E1-4 때 우리 서버에 보낸 클라이언트 키) | `14795` (DB id) | 403 `FORBIDDEN_REQUEST` | `orderId` 불일치 |
| 2 | | `98ad220c-...` (1과 같은 키) | UUID | 403 `FORBIDDEN_REQUEST` | 1의 응답 replay로 추정 (3과 본문이 같은데 응답이 다름) |
| 3 | 23:44:45 | `confirm-14795` (토스가 모르는 키) | UUID | 400 `ALREADY_PROCESSED_PAYMENT` | 새 승인 요청으로 처리됨 |
| 4 | 23:45:29 | 없음 | UUID | 400 `ALREADY_PROCESSED_PAYMENT` | E2-2 |
| 5 | 23:48:28 | `confirm-01a21d67-...` (우리 서버가 쓴 키) | UUID | **200 `DONE`** | E2-1. 첫 승인 결과 replay |

**관찰**
1. **키가 응답을 가른다.** 승인·취소 모두, 우리 서버가 쓴 키면 200 replay, 처음 보는 키나 키 없음이면 400 `ALREADY_*`. 4-5의 전제(응답 유실 뒤 같은 키 재시도로 그 자리에서 확정)가 실제 토스에서 성립한다.
2. **토스는 4xx 응답도 키에 저장한다(추정).** 요청 2는 본문을 고쳤는데도 요청 1의 403이 그대로 왔다. 우리 서버는 거절(4xx) 뒤 같은 키로 재시도하지 않으므로 영향은 없다. 사람이 직접 보낼 때만 주의한다.
3. **조회 응답에 속도 제한 헤더가 있다:** `x-ratelimit-max-limit-per-minute: 200`. 대사 설정 `pg-calls-per-second: 5`(분당 300)보다 작아 3으로 낮췄다(intent 8절 "대사 속도 제한 값"). 2026-11-28 실측 (같은 결제 조회만 반복, 스크립트는 8절):
   - 터미널 1개 순차(8-1, 요청 사이 0.2초) 250건: **127초**(= 초당 약 1.97건, 분당 약 118건). 모두 200. `remaining`은 줄긴 했지만 191 근처까지만 내려갔다.
   - 터미널 3개 동시(8-2, 합계 초당 약 6건으로 추정): 세 터미널 모두 55~56번째(00:39:53~55)에 **429**. 이때 `remaining`은 191~193이었고 `Retry-After` 헤더는 없었다.
   - 해석: 429는 실제로 온다. 세 터미널이 같은 시점에 막혔으므로 계수는 시크릿 키(또는 IP) 단위로 합산된다. 그런데 429 시점에도 `remaining`이 190대라 **헤더 값으로는 언제 막힐지 알 수 없다**(분당 200건 말고 더 짧은 구간 한도가 있거나, 헤더가 앞단 서버 한 대 기준인 것으로 추정). 헤더를 보고 스스로 속도를 맞추는 방식은 쓸 수 없다.
   - 초당 2건은 안전, 초당 6건은 429. 대사 설정 초당 3건은 직접 재지 않았다. 순차 전송은 요청마다 응답(약 0.3초)을 기다리므로 `sleep`을 줄여도 초당 3건이 정확히 나오지 않는다. 재려면 8-3처럼 응답을 기다리지 않고 일정 간격으로 띄워야 한다. 3건을 유지한 이유: 대사가 조회하는 것은 결과 모름 건(`PAYMENT_UNKNOWN`·`CANCEL_UNKNOWN`, 5분 지난 `PAYING`·`CANCELING`)뿐이라 평소 대상이 거의 없고, 한도를 넘어도 429 → 조회 실패 3번 연속 → 회차 중단 → 1분 뒤 재개로 늦어질 뿐 틀리지 않는다. 한도 단위(시크릿 키·IP)는 확인하지 않았다. 우리 서버는 키 하나·IP 하나로 부르므로 어느 쪽이든 대사 몫은 분당 200건이다. 승인·취소 응답에는 이 헤더가 없었다.

## 8. 조회 한도 실측 스크립트

터미널에 직접 붙여 넣으면 줄바꿈(`\`) 뒤 공백 때문에 명령이 깨지므로 여기서 복사한다. 모두 **같은 결제 조회(GET)만** 반복하므로 데이터는 바뀌지 않는다. 각 터미널에서 1-3의 `AUTH`를 먼저 만든다(새 터미널에는 없다. 없으면 401에서 바로 멈춰 429로 착각한다). 429를 받았으면 1~2분 쉬었다가 다음 실험을 한다.

### 8-1. 순차 (첫 비-200에서 멈춤)

응답을 기다린 뒤 0.2초 쉬고 다음을 보낸다. 실제 속도는 초당 약 2건(250건에 127초)이다.

```bash
for i in $(seq 1 250); do
  out=$(curl -s -o /dev/null -D - -w 'STATUS %{http_code}\n' https://api.tosspayments.com/v1/payments/tviva261127n4ktw48q8pYpp -H "$AUTH")
  code=$(echo "$out" | awk '/^STATUS/{print $2}')
  echo "$i $(date +%T) $code $(echo "$out" | grep -i '^x-ratelimit-remaining' | tr -d '\r')"
  if [ "$code" != "200" ]; then
    echo "$out" | grep -iE '^(retry-after|x-ratelimit)'
    break
  fi
  sleep 0.2
done
```

### 8-2. 동시 (터미널 3개에서 8-1을 거의 같은 시각에 시작)

세 터미널에서 8-1을 함께 돌린다. 합계 초당 약 6건. 2026-11-28에는 세 터미널 모두 55~56번째에 429를 받았다.

### 8-3. 정확히 초당 3건 (대사 설정과 같은 속도, 미실행)

응답을 기다리지 않고(`&`) 0.333초마다 하나씩 띄우므로 응답 시간과 상관없이 초당 3건이 된다. 1분 동안 180건.

```bash
for i in $(seq 1 180); do
  curl -s -o /dev/null -w "$i $(date +%T) %{http_code}\n" https://api.tosspayments.com/v1/payments/tviva261127n4ktw48q8pYpp -H "$AUTH" &
  sleep 0.333
done
wait
```

출력에 `429`가 하나라도 있으면 초당 3건도 막히는 것이므로 `pg-calls-per-second`를 2로 낮춘다. 없으면 3을 유지한다.

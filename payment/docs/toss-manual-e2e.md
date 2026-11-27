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

**토스 호출 주의:** 이전에 토스에서 요청이 차단된 적이 있다. 토스로 가는 요청은 이 문서의 절차만큼만 보내고, 요청 사이에 몇 초 간격을 둔다.

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

### E2-1. 같은 키 (사용자 터미널)

```bash
curl -s -i -X POST https://api.tosspayments.com/v1/payments/confirm \
  -H "$AUTH" -H "Content-Type: application/json" \
  -H "Idempotency-Key: confirm-{ORDER_NO}" \
  -d '{"paymentKey":"{PAYMENT_KEY}","orderId":"{ORDER_NO}","amount":1000}'
```
- 기대: 200, `status=DONE`. E1-4의 응답과 `approvedAt`이 같으면 토스가 새로 처리하지 않고 첫 응답을 돌려준 것이다.
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

E1-2와 같다. 기록: `ORDER_ID_2`, `ORDER_NO_2`. 재고는 5여야 한다.

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
- 기대 DB: 주문 `FAILED`, Payment `FAILED` + `failure_code`, 재고 **5** (차감했다가 복구)
- 기록: 토스가 준 상태 코드와 `code`. 이전 실제 테스트에서는 403 `FORBIDDEN_REQUEST`가 왔다(`TossPaymentClient`의 `CONFIG_ERROR_CODES` 주석). 같은지 확인한다.
- 다른 코드가 오면 그 코드를 우리가 어떻게 분류하는지(거절 / 연동 오류 / 결과 모름) 확인한다.

## 7. 결과 기록

실행하면서 채운다. 응답 본문은 필요한 필드만 남기고, 시크릿 키나 `Authorization` 헤더는 남기지 않는다.

**실행 일시:** 2026-11-27 20:38 (상품 등록) ~ 20:53 (취소)
**E2용 두 번째 승인:** ORDER_ID=14794 / ORDER_NO=01a21cee-2425-7008-9f09-a62b7f73bceb / PAYMENT_KEY=tviva261127oLXku3kY3PvPq (21:18:54 승인, paymentId=14793)
**ID:** PRODUCT_ID=2110 / ORDER_ID=14792 / ORDER_NO=01a21cd2-a26d-70ab-8fc6-dad3b1edc7a6 / PAYMENT_KEY=tviva261127n4ktw48q8pYpp / CANCEL_ID=2 / ORDER_ID_2= / PAYMENT_KEY_2=

| 단계 | 기대 | 실제 (응답 코드, 주요 필드) | DB 상태 (주문 / Payment / 재고) | 결과 |
|---|---|---|---|---|
| E1-1 상품 등록 | 201, stock=5 | `id=2110`, `stock=5`, `status=ON_SALE` (20:38:22) | 재고 5 | 통과 |
| E1-2 주문 | 201, PENDING | `id=14792`, `status=PENDING`, `totalPrice=1000` (20:44:55) | PENDING / 없음 / 5 | 통과 |
| E1-4 승인 | 200, PAID / APPROVED | `orderStatus=PAID`, `paymentStatus=APPROVED`, `amount=1000` (Idempotency-Key는 결제창 페이지가 만든 UUID). 재시도·WARN 로그 없음 | 취소 뒤 확인: `approved_amount=1000`, `failure_code` 없음 | 통과 |
| E2-1 같은 키 승인 | 200, DONE, 첫 응답과 같음 | 미실시 (E1-4 뒤 바로 취소함) | 변화 없음 | 미실시 |
| E2-2 키 없이 승인 | 4xx ALREADY_PROCESSED_PAYMENT | 미실시 | 변화 없음 | 미실시 |
| E1-5 취소 | 200, CANCELED / CANCELED | `orderStatus=CANCELED`, `paymentStatus=CANCELED`, `amount=1000`. 20:53:27 요청 → 20:53:29 반영, `ALREADY_CANCELED` 경고 없음 | CANCELED / CANCELED / 5, `payment_cancel` id=2 `COMPLETED` | 통과 |
| E1-6 개발자센터 | 취소 | 취소 (사용자 확인) | | 통과 |
| E3-1 같은 키 취소 | 200, CANCELED, 첫 응답과 같음 | | 변화 없음 | |
| E3-2 다른 키 취소 | 4xx ALREADY_CANCELED_PAYMENT | | 변화 없음 | |
| E4 조회 | 200, CANCELED, totalAmount=1000 | | | |
| E5-3 금액 다른 승인 | 4xx 거절 | | FAILED / FAILED / 5 | |
| 5xx 멱등키 저장 | 확인 불가 | 미확인 | | |
| 추가: 만료 (주문 14793, 21:14:42 생성) | 30~35분 뒤 EXPIRED, Payment 없음, 재고 그대로 | 대기 중 (예상 21:48:28 회차) | | |
| 추가: 우리 서버에 같은 키로 승인 재전송 (주문 14794) | 200, 첫 결과 replay, 토스 호출 없음 | 21:19:05, 21:19:43 두 번 200. 두 요청 모두 SELECT만 하고 쓰기 없음 | 변화 없음 (PAID / APPROVED / 4) | 통과 |
| 추가: 키 없이 승인 (주문 14794) | 400 | `COMMON_002` (`MissingRequestHeaderException`), 21:20 두 번 | 변화 없음 (PAID / APPROVED / 4) | 통과 |

**가짜 PG와 다르게 동작한 것:** (phase 완료 질문 "가짜 PG에서는 통과했는데 실제 PG에서 처음 깨진 가정은 무엇이었나?"의 답이 된다)

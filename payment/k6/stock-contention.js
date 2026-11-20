// 재고 경합 시 "승인 후 취소" 재현 (docs/troubleshooting/payment-stock-deduction-timing.md)
//
// 재고 STOCK개짜리 상품 PRODUCTS개에 주문 ORDERS건을 나눠 만들고, VU ORDERS개가 각자 자기 주문을 동시에 결제 승인한다.
// 주문은 상품에 차례로 돌아가며 붙는다. (주문 i → 상품 i % PRODUCTS) 그래서 상품 하나에 몰리는 요청은 ORDERS / PRODUCTS건이다.
// HTTP 응답(200/409)만으로는 진 요청이 PG까지 갔는지 보이지 않으므로, 끝난 뒤 가짜 PG 호출 횟수를 읽는다.
//
// 옵션 (모두 생략 가능, 기본값은 예전 동작과 같다)
//   ORDERS      주문 수 = 승인 요청 수            (기본 10)
//   STOCK       상품 하나당 재고                  (기본 1)
//   PRODUCTS    상품 수                           (기본 1)
//   SEQUENTIAL  true면 VU 1개가 주문 순서대로 하나씩 승인한다. 동시성 없이 "이긴 요청 → 진 요청"을 재는 용도 (기본 false)
//
// 전제: 서버가 가짜 PG 모드(app.pg.provider=fake, 기본값)로 떠 있어야 한다. (/api/fake-pg/* 가 있어야 함)
// 실행: k6 run k6/stock-contention.js
//       k6 run -e ORDERS=100 -e STOCK=1 -e BASE_URL=http://localhost:8080 k6/stock-contention.js
//       k6 run -e ORDERS=200 -e STOCK=1 -e PRODUCTS=200 k6/stock-contention.js      # 해피, 상품 행 경합 없음
//       k6 run -e ORDERS=200 -e STOCK=1 -e PRODUCTS=100 k6/stock-contention.js      # 경합, 상품마다 2건
//       k6 run -e ORDERS=2 -e STOCK=1 -e SEQUENTIAL=true k6/stock-contention.js     # 이긴 요청 → 진 요청 순차
import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ORDERS = parseInt(__ENV.ORDERS || '10', 10);
const STOCK = parseInt(__ENV.STOCK || '1', 10);
const PRODUCTS = parseInt(__ENV.PRODUCTS || '1', 10);
const SEQUENTIAL = (__ENV.SEQUENTIAL || 'false') === 'true';
const PRICE = 10000;

if (PRODUCTS < 1 || PRODUCTS > ORDERS) {
    throw new Error(`PRODUCTS는 1 이상 ORDERS(${ORDERS}) 이하여야 한다. (현재 ${PRODUCTS})`);
}
const JSON_HEADERS = { 'Content-Type': 'application/json' };

const approved = new Counter('approve_200');
const conflicted = new Counter('approve_409');
const others = new Counter('approve_other');
const successLatency = new Trend('approve_latency_success', true);
const failureLatency = new Trend('approve_latency_failure', true);

export const options = {
    scenarios: {
        contention: {
            executor: 'per-vu-iterations',
            // 동시: VU ORDERS개가 1번씩 / 순차: VU 1개가 ORDERS번
            vus: SEQUENTIAL ? 1 : ORDERS,
            iterations: SEQUENTIAL ? ORDERS : 1,
            maxDuration: '2m',
        },
    },
};

export function setup() {
    const reset = http.post(`${BASE_URL}/api/fake-pg/reset`);
    if (reset.status !== 204) {
        fail(`가짜 PG 초기화 실패(status=${reset.status}). 서버가 fake 모드로 떠 있는지 확인하라.`);
    }

    const runId = Date.now();
    const productIds = [];
    for (let p = 0; p < PRODUCTS; p++) {
        const product = http.post(`${BASE_URL}/api/products`,
            JSON.stringify({ name: `k6-contention-${runId}-${p}`, price: PRICE, stock: STOCK }),
            { headers: JSON_HEADERS });
        if (product.status !== 201 && product.status !== 200) {
            fail(`상품 등록 실패(${p}): ${product.status} ${product.body}`);
        }
        productIds.push(product.json('id'));
    }

    const orders = [];
    for (let i = 0; i < ORDERS; i++) {
        const productId = productIds[i % PRODUCTS];
        const res = http.post(`${BASE_URL}/api/orders`,
            JSON.stringify({ memberId: 100 + i, productId: productId, quantity: 1 }),
            { headers: JSON_HEADERS });
        if (res.status !== 201 && res.status !== 200) {
            fail(`주문 생성 실패(${i}): ${res.status} ${res.body}`);
        }
        orders.push({ id: res.json('id'), amount: res.json('totalPrice') });
    }
    // analyze_timing.py --since-order-id / --until-order-id 로 이번 실행만 집계할 때 쓴다.
    console.log(`준비 완료: 상품 ${PRODUCTS}개(id ${productIds[0]}~${productIds[PRODUCTS - 1]}) × 재고 ${STOCK}, `
        + `주문 ${ORDERS}건(orderId ${orders[0].id}~${orders[ORDERS - 1].id}), ${SEQUENTIAL ? '순차' : '동시'} 승인`);
    return { productIds, orders };
}

export default function (data) {
    const order = data.orders[SEQUENTIAL ? __ITER : __VU - 1];
    const res = http.post(`${BASE_URL}/api/orders/${order.id}/payments/approve`,
        JSON.stringify({ amount: order.amount, paymentKey: `k6-payment-key-${order.id}` }),
        { headers: { ...JSON_HEADERS, 'Idempotency-Key': `k6-${order.id}-${Date.now()}` } });

    if (res.status === 200) {
        approved.add(1);
        successLatency.add(res.timings.duration);
    } else {
        if (res.status === 409) {
            conflicted.add(1);
        } else {
            others.add(1);
            console.warn(`예상 밖 응답: orderId=${order.id}, status=${res.status}, body=${res.body}`);
        }
        failureLatency.add(res.timings.duration);
    }
    check(res, { '200 또는 409': (r) => r.status === 200 || r.status === 409 });
}

export function teardown(data) {
    const stats = http.get(`${BASE_URL}/api/fake-pg/stats`).json();
    const finalStocks = data.productIds.map((id) => http.get(`${BASE_URL}/api/products/${id}`).json('stock'));
    const totalStock = finalStocks.reduce((sum, stock) => sum + stock, 0);
    const negative = finalStocks.filter((stock) => stock < 0).length;
    console.log('================ 결과 ================');
    console.log(`상품 ${PRODUCTS}개 × 재고 ${STOCK} / 주문 ${ORDERS}건 ${SEQUENTIAL ? '순차' : '동시'} 승인 `
        + `(상품 하나당 요청 약 ${ORDERS / PRODUCTS}건)`);
    console.log(`PG 승인 호출: ${stats.approveCalls}회 (경합에서 진 요청도 포함)`);
    console.log(`PG 취소 호출: ${stats.cancelCalls}회 (= 승인 후 보상 취소)`);
    console.log(`최종 재고 합: ${totalStock} (처음 ${PRODUCTS * STOCK}), 음수 재고 상품: ${negative}개`);
    console.log('HTTP 200/409 건수와 성공·실패별 응답 시간은 아래 요약의 approve_* 지표를 본다.');
    console.log('======================================');
}

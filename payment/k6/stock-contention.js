// 재고 경합 시 "승인 후 취소" 재현 (docs/troubleshooting/payment-stock-deduction-timing.md)
//
// 재고 STOCK개 상품에 주문 ORDERS건을 만들고, VU ORDERS개가 각자 자기 주문을 동시에 결제 승인한다.
// HTTP 응답(200/409)만으로는 진 요청이 PG까지 갔는지 보이지 않으므로, 끝난 뒤 가짜 PG 호출 횟수를 읽는다.
//
// 전제: 서버가 가짜 PG 모드(app.pg.provider=fake, 기본값)로 떠 있어야 한다. (/api/fake-pg/* 가 있어야 함)
// 실행: k6 run k6/stock-contention.js
//       k6 run -e ORDERS=100 -e STOCK=1 -e BASE_URL=http://localhost:8080 k6/stock-contention.js
import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ORDERS = parseInt(__ENV.ORDERS || '10', 10);
const STOCK = parseInt(__ENV.STOCK || '1', 10);
const PRICE = 10000;
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
            vus: ORDERS,
            iterations: 1,
            maxDuration: '2m',
        },
    },
};

export function setup() {
    const reset = http.post(`${BASE_URL}/api/fake-pg/reset`);
    if (reset.status !== 204) {
        fail(`가짜 PG 초기화 실패(status=${reset.status}). 서버가 fake 모드로 떠 있는지 확인하라.`);
    }

    const product = http.post(`${BASE_URL}/api/products`,
        JSON.stringify({ name: `k6-contention-${Date.now()}`, price: PRICE, stock: STOCK }),
        { headers: JSON_HEADERS });
    if (product.status !== 201 && product.status !== 200) {
        fail(`상품 등록 실패: ${product.status} ${product.body}`);
    }
    const productId = product.json('id');

    const orders = [];
    for (let i = 0; i < ORDERS; i++) {
        const res = http.post(`${BASE_URL}/api/orders`,
            JSON.stringify({ memberId: 100 + i, productId: productId, quantity: 1 }),
            { headers: JSON_HEADERS });
        if (res.status !== 201 && res.status !== 200) {
            fail(`주문 생성 실패(${i}): ${res.status} ${res.body}`);
        }
        orders.push({ id: res.json('id'), amount: res.json('totalPrice') });
    }
    console.log(`준비 완료: productId=${productId}, 재고=${STOCK}, 주문=${ORDERS}건`);
    return { productId, orders };
}

export default function (data) {
    const order = data.orders[__VU - 1];
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
    const product = http.get(`${BASE_URL}/api/products/${data.productId}`).json();
    console.log('================ 결과 ================');
    console.log(`재고 ${STOCK} / 주문 ${ORDERS}건 동시 승인`);
    console.log(`PG 승인 호출: ${stats.approveCalls}회 (경합에서 진 요청도 포함)`);
    console.log(`PG 취소 호출: ${stats.cancelCalls}회 (= 승인 후 보상 취소)`);
    console.log(`최종 재고: ${product.stock}`);
    console.log('HTTP 200/409 건수와 성공·실패별 응답 시간은 아래 요약의 approve_* 지표를 본다.');
    console.log('======================================');
}

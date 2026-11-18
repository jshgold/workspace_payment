// 결제 경합이 요청 스레드를 붙잡아 무관한 API(상품 단건 조회)까지 느려지는지 측정한다.
// (docs/troubleshooting/payment-stock-deduction-timing.md)
//
// 시나리오
//   browse: 처음부터 끝까지 BROWSE_RPS로 GET /api/products/{id}를 호출한다. (결제 전 구간이 기준선)
//   pay   : PAY_START초 뒤 주문 ORDERS건을 동시에 결제 승인한다. (재고 STOCK)
//
// 요청마다 시작·종료 시각(ms)을 태그로 남기므로, --out json 결과를 k6/analyze_starvation.py로 구간별로 나눈다.
//   k6 run --out json=<파일> k6/thread-starvation.js
//   python3 -I k6/analyze_starvation.py <파일>
//
// 전제: 서버가 가짜 PG 모드로 떠 있어야 한다. 의미 있는 결과를 보려면 PG 지연을 준다. (--app.pg.fake.delay=300ms)
import http from 'k6/http';
import exec from 'k6/execution';
import { fail } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ORDERS = parseInt(__ENV.ORDERS || '100', 10);
const STOCK = parseInt(__ENV.STOCK || '1', 10);
const BROWSE_RPS = parseInt(__ENV.BROWSE_RPS || '20', 10);
const PAY_START_SEC = parseInt(__ENV.PAY_START || '3', 10);
const DURATION_SEC = parseInt(__ENV.DURATION || '15', 10);
const PRICE = 10000;
const JSON_HEADERS = { 'Content-Type': 'application/json' };

export const options = {
    setupTimeout: '5m',
    scenarios: {
        browse: {
            executor: 'constant-arrival-rate',
            exec: 'browse',
            rate: BROWSE_RPS,
            timeUnit: '1s',
            duration: `${DURATION_SEC}s`,
            preAllocatedVUs: 50,
            maxVUs: 500,
        },
        pay: {
            executor: 'per-vu-iterations',
            exec: 'pay',
            startTime: `${PAY_START_SEC}s`,
            vus: ORDERS,
            iterations: 1,
            maxDuration: '3m',
        },
    },
};

export function setup() {
    const reset = http.post(`${BASE_URL}/api/fake-pg/reset`);
    if (reset.status !== 204) {
        fail(`가짜 PG 초기화 실패(status=${reset.status}). 서버가 fake 모드로 떠 있는지 확인하라.`);
    }
    const product = http.post(`${BASE_URL}/api/products`,
        JSON.stringify({ name: `k6-starvation-${Date.now()}`, price: PRICE, stock: STOCK }),
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

export function browse(data) {
    const start = Date.now();
    http.get(`${BASE_URL}/api/products/${data.productId}`, {
        tags: { kind: 'browse', start_ms: String(start) },
    });
}

export function pay(data) {
    // __VU는 browse 시나리오와 번호를 공유하므로, 이 시나리오 안에서 고유한 반복 번호로 주문을 고른다.
    const order = data.orders[exec.scenario.iterationInTest];
    const start = Date.now();
    const res = http.post(`${BASE_URL}/api/orders/${order.id}/payments/approve`,
        JSON.stringify({ amount: order.amount, paymentKey: `k6-payment-key-${order.id}` }),
        {
            headers: { ...JSON_HEADERS, 'Idempotency-Key': `k6-${order.id}-${start}` },
            tags: { kind: 'pay', start_ms: String(start) },
        });
    // 결과 분류(재고 부족 / 락 타임아웃 등)는 응답 본문이 필요하므로 여기서 한 줄 로그로 남긴다.
    console.log(`PAY_RESULT start=${start} end=${Date.now()} status=${res.status} outcome=${classify(res)}`);
}

function classify(res) {
    if (res.status === 200) return 'success';
    const body = res.body || '';
    if (res.status === 409 && body.includes('재고가 부족')) return 'stock_shortage';
    if (res.status === 503 && body.includes('COMMON_006')) return 'lock_timeout';
    if (res.status === 0) return 'transport_error';
    return `other_${res.status}`;
}

export function teardown(data) {
    const stats = http.get(`${BASE_URL}/api/fake-pg/stats`).json();
    const product = http.get(`${BASE_URL}/api/products/${data.productId}`).json();
    console.log(`PG_STATS approve=${stats.approveCalls} cancel=${stats.cancelCalls} final_stock=${product.stock}`);
}

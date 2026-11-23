# 트러블슈팅 이어서 하기 (작업 인계)

> 마지막 갱신: 2026-11-21. 새 세션은 이 파일부터 읽고 시작한다.
> 이 작업은 phase-04 체크리스트 밖의 트러블슈팅이다. 개선 A는 적용·merge했고, 개선 B는 phase-04 **4-2+(결제 모델 재설계)**에 포함해 진행한다. (3번 참고)

## 0. 지금 할 일 (2026-11-21)

**X / A / B를 같은 조건으로 다시 재서 트러블슈팅을 닫는다. 그다음 phase-04 4-3으로 넘어간다.**
계획은 `test-list.md` 1부에 있다. (시나리오, 측정 버전, 고정 조건, 지표, 실행 명령)

**왜 다시 재나 (사용자와 합의한 것)**
- 이전 측정은 질문이 생길 때마다 만든 테스트라 버전마다 시나리오·횟수·지표가 다르다. (A 단건은 1회, 구간별 값 없음 / B 트랜잭션 수는 실측 안 함)
- X, A, B는 "승인 흐름만 있는 같은 단계"의 세 버전이라, 비교도 이 단계에서 끝낸다. phase-04를 끝낸 뒤 재면 다른 버전이 된다. (최종 버전 측정은 그때 별도 기준선으로)
- 이전 해피 케이스는 모두 상품 1개라 커넥션 대기와 상품 행 락 대기가 섞여 있었다. 이번에는 "상품 여러 개에 나눠 요청"으로 둘을 나눈다. (시나리오는 사용자가 제안)
- 기존 조사 문서는 덮어쓰지 않는다. 재측정 결과는 `test-list.md` 1-8에만 채우고, 기존 해석과 어긋나는 곳에만 메모를 단다.

**순서**
1. [x] 시나리오 목록 확정 (`test-list.md` 1-5)
2. [x] X·A·B 승인 API 형식 비교 → 같음, k6 스크립트 하나로 잰다 (1-7)
3. [x] k6 `stock-contention.js`에 `PRODUCTS`, `SEQUENTIAL` 옵션 추가 (`k6 inspect`로만 확인, 실제 실행은 아직)
4. [ ] 반복 실행(워밍업 + 3회)과 실행 구간 구분 방법 — 같은 명령 4번 + 출력되는 orderId 범위로 `analyze_timing.py` 집계하는 방안
5. [ ] 버전별 worktree와 빈 DB 준비 방법 결정 — 로컬 DB는 V9 적용돼 X(`2f25076`)·A(`5e7823f`)가 뜨지 않는다. MySQL 안 새 스키마 vs 별도 컨테이너. 기존 `payment` DB는 건드리지 않는다. **방법을 정리해 사용자에게 먼저 확인받는다.**
6. [x] 작은 시나리오(H1, L1, C2)로 스크립트·집계 동작 확인 (2026-11-22, B) — 정상. 재기동 직후 첫 요청이 크게 느려 워밍업 필수.
7. [ ] 버전별로 전체 시나리오 측정, `test-list.md` 1-8에 기록 — 순서를 "버전 하나씩 전부"로 바꿨다 (서버 전환 3번). 버전별 빈 DB `payment_b`/`payment_a`/`payment_x`는 사용자가 만들었다(2026-11-22). 접속 DB는 `application-local.yaml`의 URL로 바꾼다.
   - [x] B (`payment_b`) — 2026-11-22, `test-list.md` 1-8-B. 주요 발견: 동시 200건에서 상품을 나누면 395ms, 한 상품이면 590ms. 2-7의 "상품 행 락 대기 거의 없음"은 틀린 것으로 보임
   - [x] A (`5e7823f` worktree + `payment_a`) — 2026-11-23, `test-list.md` 1-8-A. 주요 발견: 상품을 나눈 200건에서 A 480 → B 395ms(커넥션 대기 절반). 상품 하나면 A·B 같음(588/590ms). C200-집중은 A가 빠름. worktree는 세션 임시 폴더 `wt-a` (정리 전)
   - [x] X (`2f25076` worktree + `payment_x`) — 2026-11-23, `test-list.md` 1-8-X (X→A→B 비교표 포함). 주요 발견: A는 경합 비용(진 요청 655~792 → 18~230ms, PG 낭비 0)을, B는 상품 행 하나에 몰리지 않은 200건의 커넥션 대기를 절반으로 줄였다(재고 경합 있는 C200-분산도 절반). H200-공유는 X 701 → A 588ms. worktree는 세션 임시 폴더 `wt-x` (정리 전)
   - 실행은 시나리오 순서 고정 스크립트로 한다. `zsh k6/run-all.sh <결과 폴더>` → `python3 -I k6/summarize.py <결과 폴더> <서버 로그> k6/analyze_timing.py` (2026-11-23 세션 임시 폴더에서 `k6/`로 옮김. B 원본으로 다시 집계해 같은 결과 확인)
   - A·X 코드는 `git worktree add <폴더> <커밋>`으로 다른 폴더에 꺼내 띄운다. 지금 작업 폴더는 바뀌지 않는다. 끝나면 `git worktree remove <폴더>`.
8. [x] T1: 트랜잭션 수 실측 (SQL 로그·Hibernate 통계, 시간 측정과 따로) — 2026-11-23, `test-list.md` 1-8-T1. 추정치 모두 맞음 (이긴 요청 X 4 / A 4 / B 2, 진 요청 X 5 / A 3 / B 1). B 진 요청만 상품 행 락을 쥔 채 INSERT payment·UPDATE orders·COMMIT. 스크립트 `k6/t1-single.sh`, `k6/t1_summary.py`
9. [ ] 결과 정리 후 phase-04 4-3 진행
   - 정리본: `payment-x-a-b-summary.md` (2026-11-24 작성, 7절 결론은 사용자 작성 대기)

**같은 세션에서 한 다른 작업 (커밋 안 함)**
- phase-04 4-2+ 마지막 항목 "같은 키 + 다른 금액" → 거절로 구현. `PaymentReplayer`에 금액 비교 추가(요청 금액 불일치로 실패한 시도는 제외), `IDEMPOTENCY_KEY_REUSED` 메시지 확장, 테스트 `keyReusedWithDifferentAmountIsRejected`. 결제 패키지 테스트 117개 통과. 4-2+ `[x]`.
  - `intent.md` 8절 해당 줄의 **이유 칸은 사용자 작성 대기**.
- `test-list.md` 새로 작성, `stock-contention.js` 옵션 추가.

## 1. 진행 원칙 (사용자와 합의한 것)

- 현재 결제 구조를 **"설계 실패 버전"**으로 보고, 문제를 **재현·측정한 뒤** 개선한다.
- **개선(설계 변경)은 사용자가 지시하기 전까지 적용하지 않는다.**
- **현재 버전을 검증하기 위한 계측은 추가해도 된다.** 로그, 카운터, 측정 전용 엔드포인트, 테스트 대역 설정 등. 비즈니스 흐름은 바꾸지 않는다.
- 계측 방법이나 테스트 조건은 **실행 전에 무엇을 왜 테스트하는지 먼저 설명**하고 시작한다.
- 재설계안(`docs/payment-redesign-discussion.md`)은 별개의 수정이다. 트러블슈팅 문서에 섞지 않는다.
- 측정 결과가 예상과 다르면 그대로 기록하고, 앞선 해석을 고친다. (확인한 것과 추정을 구분해 적는다)
- 응답 시간은 **고객 1명이 체감하는 건당 응답 시간**을 기준 지표로 본다. (전체 처리 시간이 아니라)

## 2. 개선 전 측정 (A 적용 전 구조 기준)

두 테스트는 서로 다른 문제를 짚었고, 그래서 개선도 두 개로 나뉜다.
| 문서 | 상태 | 요약 |
|---|---|---|
| `payment-stock-deduction-timing.md` | **개선 A 적용·측정 완료** | 재고를 PG 승인 뒤에 차감 → 재고 1에 N건이면 PG 승인 N, 취소 N-1. A 적용 후 승인 1 / 취소 0. |
| `payment-transaction-granularity.md` | **A 후 측정 완료, B는 4-2+에서** (병목 = 커넥션 풀, 2-7·2-8) | 이긴 요청 트랜잭션 4번, 매번 커넥션 대기. A 후 200건 요청당 커넥션 대기 264ms가 B의 "개선 전" 기준선. |
| `product-list-pagination.md` | 완료 (phase-01) | 이번 작업과 무관 |

### 첫 번째 테스트: 경합 때 PG 호출 낭비 → `payment-stock-deduction-timing.md`
**질문:** 재고를 PG 승인 **뒤에** 차감하는 현재 구조에서, 경합이 나면 PG 호출 때문에 어떤 비용이 생기나?
- 재고 1에 주문 N건이면 **PG 승인 N번, 취소 N−1번.** (JUnit·k6·DB로 확인)
- 진 사용자는 결과가 이미 정해졌는데도 PG 승인·취소 왕복(약 600ms)을 다 기다린 뒤 실패를 받는다.
- 결제가 몰리면 상품 조회도 10ms대 → 1초 이상으로 느려지지만, **해피 케이스에서도 똑같이 느려져서 경합 탓이 아니다**라고 결론을 고쳤다. → 두 번째 테스트의 계기

### 두 번째 테스트: DB 병목의 크기와 위치 → `payment-transaction-granularity.md`
**질문:** 경합이 아니라면, 결제가 몰릴 때 무엇이 시간을 잡아먹나?

| 동시 요청 (결제만, PG 지연 300ms) | 고객 체감 건당 평균 | DB 구간 | 그중 커넥션 대기 |
|---|---|---|---|
| 1 (단건) | 0.35초 | 45ms | 0ms |
| 200 | 0.66~0.69초 | 약 360~376ms | 342ms |
| 1000 | 2.0초 | 약 362ms | (미측정, 200건과 같을 것으로 봄) |

- **병목 = 커넥션 풀(10개) 대기.** 200건에서 DB 구간 376ms 중 커넥션 대기 342ms, COMMIT 8ms, 나머지(SQL·락) 26ms. COMMIT과 상품 행 락 대기는 늘지 않았다. (문서 2-7)
- **대기는 트랜잭션 수만큼 곱해진다.** 요청 1건이 트랜잭션 4~5번 = 커넥션 4~5번 빌림, 매번 약 85ms 대기. (문서 2-1, 2-7)
- **200건을 넘는 요청은 DB가 아니라 스레드(200개) 앞에서 줄을 선다.** 1000건의 2초는 "스레드가 요청 1건을 쥐는 시간 0.66초 × 줄 선 묶음 수"다. (문서 2-5)
- **처리량 한계는 약 300건/초.** 초당 요청이 이보다 많으면 줄이 계속 길어진다. (문서 2-6)
- 커넥션 풀 크기는 DB 서버 스펙(코어·디스크, `max_connections`, 서버 대수)을 따라가는 인프라 상수에 가깝다. 앱이 바꿀 수 있는 것은 **요청당 트랜잭션 수**다. 또 지금은 풀(10개)이 상품 행 락 앞의 줄을 막아 주고 있어서, 풀을 키우면 병목이 상품 행 락으로 옮겨갈 수 있다. (확인하지 않은 예상)

부수적으로 확인한 것:
- 진 요청도 `payment` 행을 INSERT한 뒤 롤백한다. (IDENTITY라 `save()` 즉시 INSERT, 재고 판정보다 앞) 결제 ID가 비는 것으로 확인.
- UPDATE는 커밋 직전 flush에서 나가고, 모든 컬럼을 쓴다. 상품 UPDATE에는 `version` 조건이 붙는다. (비관적 락 경로에서도 `@Version` 검사)
- **설명하지 못한 것:** `complete`의 "나머지"(SQL·코드)가 단건에서도 약 18ms로 다른 트랜잭션(3~7ms)보다 길다.

## 3. 개선 A 적용·측정 완료, B는 phase-04 4-2+에 포함


> A의 전후 비교 정리본(글 작성용): `payment-stock-before-pg-result.md`

**2026-11-19 A 적용** (사용자 지시)
- `startPaying`: 주문 선점 + 재고 차감 (orders → product 순서로 잠금). 재고 부족이면 선점까지 롤백되어 PG를 부르지 않는다.
- `complete`: 주문 `PAID` + `Payment` 저장만 한다. 상품 행을 건드리지 않는다.
- `revertToPending`: 선점 해제 + 재고 복구. 원자적 쿼리 구현용으로 `ProductRepository.increaseStock` 벌크 UPDATE를 추가했다.
- 가짜 PG에 `willApproveWithAmount(long)`를 추가했다. 승인 후 보상 경로를 재현하던 "complete에서 재고 부족"이 없어졌기 때문에, 금액 불일치로 같은 경로를 탄다.
- JUnit: `stockContentionApprovesOnlyWinner()` (구 `stockContentionCausesApproveThenCancel()`) 두 구현 모두 PG 승인 1 / 취소 0. payment·product·order 패키지 테스트 247개 통과. 전체 테스트(`./gradlew test`)도 사용자가 실행해 통과했고, PR로 `main`에 merge했다. (2026-11-20)
- 바뀐 동작: PG 타임아웃·보상 취소 실패로 `PAYING`에 남은 주문은 재고를 쥐고 있다. (`pgTimeout`, `compensationFails` 기대 재고 3)

**2026-11-19 A 후 k6 측정** (각 1회, PG 지연 300ms)
| | 개선 전 | 개선 A 후 |
|---|---|---|
| 경합 100건: PG 승인 / 취소 | 100 / 99 | **1 / 0** |
| 경합 100건: 실패 응답 평균 | 779ms | **216ms** (그중 커넥션 대기 173ms, 트랜잭션 3번) |
| 해피 단건 | 0.35초 | 0.35초 |
| 해피 200건 | 0.66~0.69초 (커넥션 대기 342ms) | 0.60초 (커넥션 대기 264ms) |
| 해피 1000건 | 2.0초 | 1.85초 |
- 상품 행 작업의 "나머지"(약 16ms)는 `complete` → `startPaying`으로 크기 그대로 옮겨 갔다. PG 앞으로 옮겨도 상품 행 락 대기는 커지지 않았다.
- 예상(해피 케이스 "그대로")과 달리 조금 빨라졌다. 커넥션 대기가 PG 뒤 트랜잭션에서 거의 사라졌다. 해석은 `payment-transaction-granularity.md` 2-8 (확인 안 함, 단발 측정).
- 단건 `complete` 나머지가 상품 행 없이도 14.9ms → "18ms = 상품 행 처리" 추측은 맞지 않아 보임. 반복 측정 필요.
- 구간 집계는 `k6/analyze_timing.py`로 한다. (`--since-order-id`, `--until-order-id`로 실행 구간을 나눔)

**2026-11-19 A 후 스레드 고갈 재측정** (`thread-starvation.js`, 상품 조회 "결제 중" 평균, 개선 전 → 후)
- 스레드 20·경합 100: 1,516 → 68ms / 스레드 200·경합 500: 606 → 328ms / 경합 1000: 1,333 → 591ms / **해피 1000: 1,430 → 1,224ms**
- 경합 쪽 지연은 크게 줄었고 해피는 조금 줄었다. → 개선 전 결론 "경합 탓이 아니다"를 고쳤다: 경합 비용도 있었고 A가 그것을 없앴다. 남은 지연은 결제 몰림 자체의 비용(요청당 트랜잭션 수 × 커넥션 대기 → 스레드 줄)이다.
- 경합 1000 실패 응답 777ms = 서버 안 261ms(커넥션 대기 247) + 스레드 줄 약 516ms (추정)
- 결제 중 상품 조회 표본이 적다 (7~62건). 상세는 `payment-stock-deduction-timing.md` "개선 A 후 같은 측정".

**2026-11-20 개선 B(4-2+) 측정 결과** (`payment-transaction-granularity.md` 2-9)
- 트랜잭션 4 → 2번(진 요청 3 → 1번)이 됐지만 **부하 중 커넥션 대기와 고객 체감은 줄지 않았다.** (200건 264 → 278ms, 0.60 → 0.62초 / 1000건 1.85 → 1.92초 / 경합 실패 응답 216 → 214ms)
- 단건만 줄었다. (DB 구간 43.5 → 20.8ms, 고객 체감 0.35 → 0.33초)
- 2-7의 "대기는 트랜잭션 수만큼 곱해진다"는 틀린 것으로 보인다. 몰린 요청의 대기는 "요청당 DB 작업 시간(약 30ms) × 앞에 선 요청 수 ÷ 커넥션 10개"로 설명된다. (해석, 확인 안 함)
- 다음 후보(결정은 사용자): `startPaying`의 나머지 약 25ms가 무엇인지 계측(상품 행 락 대기 등), 풀 크기를 바꿔 보는 실험.
- 로컬 DB에는 V9가 2026-11-20 03:13에 적용됐다. 구현 중 컴파일할 때 떠 있던 이전 서버가 devtools로 재시작하면서 적용했다. (결제 4,623행 `APPROVED`로 이관, `legacy-` 키 0건, `idempotency_key` 테이블 삭제)

**다음 (2026-11-20 결정):**
1. **B는 따로 하지 않고 phase-04 4-2+로 진행한다.** 하네스 재설계(d6c9b97)로 멱등키가 `payment.idempotency_key` 컬럼이 되고, `startPaying`에서 멱등키·주문 선점·재고 차감·`Payment=REQUESTED`를 한 트랜잭션으로 묶는다. 이것이 B의 "멱등키 선점 + 주문 선점", "결과 기록 + 반영" 합치기와 같은 변경이다.
   - 예상: 이긴 요청 트랜잭션 4번 → 2번 (`begin`+`startPaying`, `complete`+`succeed`). 확인 안 함.
   - 4-2+ 후 같은 조건(단건, 200건, 1000건, 경합 100건)으로 재측정해 `payment-transaction-granularity.md` 7번 "개선 B 후" 칸을 채운다. 기준선은 2-8.
2. ~~단건 `complete` 나머지 반복 측정~~ — 보류. 4-2+에서 `complete`의 구성이 바뀌므로 지금 원인을 찾을 이유가 없다. 4-2+ 재측정 때도 길면 그때 본다.
3. ~~전체 테스트(`./gradlew test`) 실행 후 커밋~~ — 완료 (2026-11-20, 사용자 실행·merge)

### 개선 방식 결정 때 정리한 내용

| 개선 | 줄이는 것 | 효과가 보이는 상황 | 관련 문서 |
|---|---|---|---|
| **A. 재고 차감을 PG 앞(`startPaying`)으로** | 진 요청의 PG 승인·취소, 트랜잭션 5 → 3번 | 경합 (재고 < 주문) | `payment-stock-deduction-timing.md` |
| **B. 트랜잭션 합치기** (멱등키 선점 + 주문 선점, 결과 기록 + 반영 등) | 요청당 커넥션 대기 횟수 | 모든 상황, 특히 해피 케이스 | `payment-transaction-granularity.md` 5번, `payment-redesign-discussion.md` |

- **A만으로는 오늘 찾은 병목이 풀리지 않는다.** 이긴 요청은 여전히 트랜잭션 4번이다. (재고 차감이 `complete`에서 `startPaying`으로 자리만 옮김)
- A를 하면 새로 생기는 비용: PG 거절·금액 불일치 시 `revertToPending`에서 **재고 복구**가 필요하다. PG 타임아웃으로 `PAYING`에 남은 주문은 재고를 붙잡고 있게 되고, 이는 결제 조회·대사(phase-04의 4-4)로 정리해야 한다.
  - (2026-11-20 하네스 재설계 후) `PAYING → PENDING` 간선이 없어지므로, 재고 복구는 `revertToPending`이 아니라 `→ FAILED` 전이에서 한다. 타임아웃은 `PAYMENT_UNKNOWN`으로 가고 대사가 `PAID`(재고 그대로) 또는 `FAILED`(재고 복구)로 확정한다. (`intent.md` 상태 전이)
- 예전 걱정("A를 하면 1000건이 PG 전에 상품 행 하나에 줄을 선다")은 측정상 작아 보인다. 커넥션 풀이 DB 동시 진입을 10개로 제한하고, 지금도 `complete`의 상품 행 락 대기가 거의 없다. **A 적용 후 `startPaying`의 "나머지" 몫이 늘어나는지로 확인한다.**
- 이전 세션의 추천 순서: **A 먼저.** (결과가 PG 승인/취소 횟수로 명확하고, 오늘 계측으로 락 대기 변화도 볼 수 있다) 결정은 사용자가 한다.
- 개선 후 비교: 같은 조건(단건, 200건, 1000건, 경합 100건)으로 다시 재서 문서의 "개선 후" 칸을 채운다. JUnit `PaymentFlowTestSupport.stockContentionCausesApproveThenCancel()`의 기대값(PG 승인 N / 취소 N−1)을 승인 1 / 취소 0으로 바꾸는 것이 A의 증거다.

아직 문서로 만들지 않았던 후보 → 둘 다 phase-04 4-2+에서 다룬다:
- 결제가 됐는지 아무도 모르는 주문 (PG 타임아웃 → `PAYING` + 멱등키 `IN_PROGRESS`, `Payment` 기록 없음). → 4-2+에서 `Payment=REQUESTED`를 PG 호출 전에 남기고, 타임아웃은 `PAYMENT_UNKNOWN`/`UNKNOWN`으로 전이. 4-2+ 재현 테스트 대상.
- PG 호출 전 실패(락 타임아웃 등)인데 멱등키가 영구 `IN_PROGRESS`로 남는 문제. → 4-2+에서 멱등키가 `startPaying` 트랜잭션에 들어가므로, 그 트랜잭션이 실패하면 키도 함께 롤백된다.

## 4. 실행 방법

**서버 (가짜 PG, local 프로필)**
```
./gradlew bootRun --console=plain --args='<옵션>'
```
| 옵션 | 용도 |
|---|---|
| `--logging.level.org.hibernate.SQL=info --logging.level.org.hibernate.orm.jdbc.bind=info` | 부하 측정 시 SQL 로그 끄기 (출력 자체가 응답 시간을 왜곡) |
| `--app.pg.fake.delay=300ms` | 가짜 PG 승인·취소 지연 |
| `--logging.level.payment.timing=debug` | 구간별 소요 시간 + 커넥션 대기·COMMIT 계측 켜기 (아래 계측 참고) |
| `--server.tomcat.threads.max=20` | 스레드 고갈을 작은 규모로 재현 |
| `--spring.jpa.properties.hibernate.generate_statistics=true --logging.level.org.hibernate.session.metrics=debug` | 트랜잭션(세션)별 커넥션 획득 수·SQL 수. Hibernate 7은 두 번째 옵션이 없으면 아무것도 안 찍힌다 |
| `--logging.level.org.springframework.orm.jpa.JpaTransactionManager=debug` | 트랜잭션 시작·커밋·롤백 경계 |

**부하 측정 기본 조합:** `--logging.level.org.hibernate.SQL=info --logging.level.org.hibernate.orm.jdbc.bind=info --app.pg.fake.delay=300ms --logging.level.payment.timing=debug`

- 기동 후 확인: 로그에 `The following 1 profile is active: "local"`, 그리고 `GET /api/fake-pg/stats`가 200이면 가짜 PG가 주입된 것이다. (토스 모드면 이 엔드포인트가 없다)
- `bootRun`은 `.env`를 환경변수로 주입한다. `.env`의 `PROFILE_SETTING`이 비어 있으면 프로필이 없이 떠서 기동에 실패한다. `.env`는 읽지 않는다.
- 서버를 다시 띄울 때는 `lsof -nP -iTCP:8080 -sTCP:LISTEN -t`로 PID를 찾아 `kill <PID>`(SIGTERM)로 내린다. 코드를 바꾼 뒤에는 반드시 다시 띄운다.

**k6**
```
k6 run -e ORDERS=200 -e STOCK=200 k6/stock-contention.js    # 결제만 동시 요청. STOCK=ORDERS면 해피 케이스, STOCK=1이면 경합
k6 run --out json=<파일> -e ORDERS=1000 -e STOCK=1000 -e DURATION=25 k6/thread-starvation.js > <로그> 2>&1   # 결제 + 상품 조회 혼합 부하
python3 -I k6/analyze_starvation.py <json 파일> <로그 파일>   # 상품 조회를 결제 전/중/후로 나눠 요약
```
- 건당 응답 시간은 k6 요약의 `approve_latency_success` / `approve_latency_failure`를 본다.
- 구간별 집계: `python3 -I k6/analyze_timing.py <서버 로그> [--since-order-id N] [--until-order-id M]` — 결과별·구간별 평균/p50/p95/최대, DB 구간은 conn/commit/rest로 분해. 서버 로그는 `bootRun ... > <로그 파일> 2>&1`로 남긴다.

**측정용 계측 (현재 코드에 있음)**
- `FakePaymentClient`: `app.pg.fake.delay` 프로퍼티로 초기 지연 설정 (문자열로 받아 `DurationStyle`로 파싱. `Duration`으로 받으면 `PaymentClientSwitchTest`의 최소 컨텍스트에서 변환 실패함)
- `FakePgController`: fake 모드 전용 `GET /api/fake-pg/stats`, `POST /api/fake-pg/reset`
- `PaymentSegmentTimer` (`payment.facade`): 결제 승인 1건의 구간별 소요 시간(`begin`, `startPaying`, `pgApprove`, `complete`, `pgCancel`, `revertToPending`, `succeed`/`fail`, `etc`, `total`)을 요청당 한 줄로 남긴다. Facade 안에서 재므로 **Tomcat 스레드 대기는 포함되지 않는다.** (k6 응답 − `total` ≈ 스레드 대기)
- `JdbcTimingDataSource` / `JdbcTiming` / `JdbcTimingConfig` (`common`): `payment.timing`이 DEBUG일 때만 DataSource를 감싸, DB 구간마다 커넥션 대기와 COMMIT 시간을 붙인다.
  - 로그 예: `orderId=1 result=OK begin=95.9ms(conn=88.8ms,commit=2.5ms) startPaying=... pgApprove=301.6ms complete=... succeed=... etc=0.0ms total=677.4ms`
  - "나머지" = 구간 시간 − conn − commit = SQL 실행 + 행 락 대기 + 코드
- 둘 다 꺼져 있으면(기본값) 시간을 재지 않고 원래 코드만 실행한다.
- JUnit 경합 테스트: `PaymentFlowTestSupport.stockContentionApprovesOnlyWinner()` (개선 A 이후 기대값: PG 승인 1 / 취소 0)
- `FakePaymentClient.willApproveWithAmount(long)`: 다른 금액으로 승인해 보상 취소 경로를 재현


## 5. 환경 메모

- pre-push hook이 `./gradlew test` 전체를 돌린다. 메인/테스트 코드를 바꿨으면 푸시 전에 관련 테스트를 넓게 돌릴 것.
- 2026-11-19 세션에서 계측 코드(`PaymentSegmentTimer`, `JdbcTiming*`)를 추가했고, 결제 관련 단위·통합 테스트 48개가 통과했다. 사용자가 커밋했다. (`4156c7b`, `2f25076`) 이후 개선 A(`7c545d4`)까지 포함해 전체 테스트를 사용자가 실행해 통과했다. (2026-11-20)
- 로컬 `payment` DB에 측정 데이터가 쌓여 있다: 상품 약 24개, 주문 약 5,400건(개선 A 측정분은 orderId 4136~5436). 전부 테스트 데이터이고, 사용자는 쌓이는 것을 신경 쓰지 않는다. 지울 때만 승인을 받는다.
- 측정 원본 로그는 세션 임시 폴더에 있어 사라질 수 있다. 결과 수치는 문서에 모두 옮겨 두었다.

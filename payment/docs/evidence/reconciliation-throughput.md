# 승인 대사 회차 처리량 측정 (phase-04 4-4, 2026-11-26)

계획: `docs/payment-reconciliation-discussion.md` S절
측정 코드: `src/test/java/com/payment/payment/facade/ReconciliationThroughputMeasurementTest.java` (`@Tag("measurement")`)
실행: `./gradlew measurementTest --tests "*ReconciliationThroughputMeasurementTest" --console=plain -i` (일반 `test`에서는 제외됨)

## 조건
- 대상: `PAYMENT_UNKNOWN` 300건 (실제 승인 흐름 + 가짜 PG 승인 타임아웃으로 생성, `updated_at`을 6분 전으로 옮김)
- 가짜 PG 조회: `DONE`, 금액 = 서버 금액 → 모두 `PAID` 확정 대상
- 설정: 대사 기준 5분, 묶음 500, 회차 상한 50초, 연속 실패 3번 (운영 기본값). 초당 PG 호출 수만 시나리오별로 바꿈
- PG 지연은 대상을 만든 뒤 `FakePaymentClient.delay()`로 건다 (조회에 적용)
- 환경: 로컬 macOS, Testcontainers MySQL 8.0. 한 회차, 단일 스레드

## 결과

| 코드 | PG 지연 | 초당 | 처리(확정) | 남은 대상 | 소요 | 건당 평균 | 예상 | 일치 |
|---|---|---|---|---|---|---|---|---|
| T0 | 0ms | 1000 | 300 | 없음 | 729ms | 2.4ms | 300건 수 초 안, 건당 수 ms | 예 |
| T1 | 0ms | 5 | 251 | 있음 | 50,023ms | 199.3ms | 약 250건, 약 50초 | 예 |
| T2 | 100ms | 5 | 251 | 있음 | 50,125ms | 199.7ms | T1과 같음 | 예 |
| T3 | 500ms | 5 | 97 | 있음 | 50,169ms | 517.2ms | 약 100건 | 예 |

모든 시나리오에서 PG 실패 0, 반영 오류 0, 처리한 건 = `PAID` 주문 수.

## 원본 로그
```
[측정 T0] PG 지연=0ms, 초당=1000, 대상=300, 확정=300, PG 실패=0, 반영 오류=0, 남은 대상 있음=false, 소요=729ms, 건당 평균=2.4ms, PG 조회=300번, PAID=300건
[측정 T1] PG 지연=0ms, 초당=5, 대상=251, 확정=251, PG 실패=0, 반영 오류=0, 남은 대상 있음=true, 소요=50023ms, 건당 평균=199.3ms, PG 조회=251번, PAID=251건
[측정 T2] PG 지연=100ms, 초당=5, 대상=251, 확정=251, PG 실패=0, 반영 오류=0, 남은 대상 있음=true, 소요=50125ms, 건당 평균=199.7ms, PG 조회=251번, PAID=251건
[측정 T3] PG 지연=500ms, 초당=5, 대상=97, 확정=97, PG 실패=0, 반영 오류=0, 남은 대상 있음=true, 소요=50169ms, 건당 평균=517.2ms, PG 조회=97번, PAID=97건
```

## 읽는 법
- **DB 비용은 상한에 영향이 없다 (T0).** 건당 약 2.4ms(대상 조회 + `FOR UPDATE` + 전이 + 커밋). 속도 제한 간격 200ms의 1% 남짓이다.
- **PG 지연이 200ms보다 짧으면 속도 제한이 상한이다 (T1 = T2 = 251건).** 251 = 50초 / 200ms + 첫 호출 1건. `Pacer`는 호출 시작 간격을 맞추므로, 지연 100ms는 다음 호출까지 남은 대기 안에 흡수된다.
- **PG 지연이 200ms보다 길면 PG 지연이 상한이다 (T3 = 97건).** `Pacer`는 이미 지난 간격을 기다리지 않으므로 건당 시간 = PG 지연 500ms + DB 약 17ms. 대사는 한 스레드에서 건을 차례로 처리하므로 PG가 느려지면 처리량이 그만큼 줄어든다.
- **묶음 500은 한 회차에 다 쓰이지 않는다.** 현재 설정(초당 5, 50초)에서 한 회차 최대 약 250건이다. 묶음 하나를 다 읽기도 전에 시간 상한에 걸린다.
- 예: 토스 장애 뒤 `PAYMENT_UNKNOWN` 1,000건 → PG 정상(지연 < 200ms)이면 최소 4회차, PG가 500ms로 느리면 최소 11회차. 회차 시간에 회차 사이 간격 1분을 더하면 각각 약 6~7분(50초 × 4 + 1분 × 3), 약 19분(50초 × 10 + 약 16초 + 1분 × 10).

## F0: 결과가 FAILED일 때 (재고 복구, 상품 행 락) — 2026-11-26 추가

T0와 다른 것은 PG 조회 결과 하나다 (`DONE` → `ABORTED`). 대사 단독, 고객 부하 없음.
상품 1 × 재고 400, `PAYMENT_UNKNOWN` 300건(수량 1, 결제 시작에서 차감돼 재고 100), PG 지연 0ms, 초당 1000.

| 코드 | 결과 | 처리 | 소요 | 건당 평균 | 재고 | 예상 |
|---|---|---|---|---|---|---|
| T0 | `PAID` | 300 | 729ms | 2.4ms | 그대로 | - |
| F0 | `FAILED` + 재고 복구 | 300 | 897ms | 3.0ms | 100 → 400 | 건당 3ms 안팎, 재고 400 |

```
[측정 F0] PG 지연=0ms, 초당=1000, 대상=300, 확정=300, PG 실패=0, 반영 오류=0, 남은 대상 있음=false, 소요=897ms, 건당 평균=3.0ms, PG 조회=300번, FAILED=300건, 재고 100 → 400
```

- 상품 행 `FOR UPDATE` + 재고 UPDATE가 붙어 건당 약 0.6ms 늘었다. 각 1회 측정이라 이 차이는 오차 범위일 수 있다.
- 다투는 상대가 없는 대사 단독에서는 상품 행 락도 처리량에 영향이 없다. 초당 5(간격 200ms)에서는 3ms든 2.4ms든 T1과 같은 251건이 된다.
- 고객 결제 시작과 같은 상품 행을 다툴 때의 영향은 재지 않았다.

## 케이스별 한 회차 (2026-11-26)

측정 코드: `ReconciliationCaseRoundMeasurementTest` (`./gradlew measurementTest --tests "*ReconciliationCaseRoundMeasurementTest" -i`)
조건은 T0·F0와 같다: 대상 300건, 6분 경과, 수량 1, PG 지연 0ms, 초당 1000, 회차 상한 50초, 묶음 500, 연속 실패 3. 바꾼 것은 대상 상태와 PG 조회·취소 결과뿐이다. 23개 모두 통과(상태·재고·회차 멈춤이 예상과 같음, 반영 오류 0).

| 코드 | 대상 | 조회 결과 | 대상 수 | 확정 | 건너뜀 | PG 실패 | 남은 대상 | 소요 | 건당 | PG 조회 | PG 취소 | 주문 상태 | 재고 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A01 | PAYMENT_UNKNOWN | DONE 금액 같음 | 300 | 300 | 0 | 0 | 없음 | 815ms | 2.7ms | 300 | 0 | {PAID=300} | 100 → 100 |
| A02 | AGED_PAYING | DONE 금액 같음 | 300 | 300 | 0 | 0 | 없음 | 687ms | 2.3ms | 300 | 0 | {PAID=300} | 100 → 100 |
| A03 | PAYMENT_UNKNOWN | ABORTED | 300 | 300 | 0 | 0 | 없음 | 712ms | 2.4ms | 300 | 0 | {FAILED=300} | 100 → 400 |
| A04 | PAYMENT_UNKNOWN | EXPIRED | 300 | 300 | 0 | 0 | 없음 | 853ms | 2.8ms | 300 | 0 | {FAILED=300} | 100 → 400 |
| A05 | PAYMENT_UNKNOWN | READY | 300 | 300 | 0 | 0 | 없음 | 767ms | 2.6ms | 300 | 0 | {FAILED=300} | 100 → 400 |
| A06 | PAYMENT_UNKNOWN | IN_PROGRESS | 300 | 300 | 0 | 0 | 없음 | 791ms | 2.6ms | 300 | 0 | {FAILED=300} | 100 → 400 |
| A07 | PAYMENT_UNKNOWN | NOT_FOUND_PAYMENT | 300 | 300 | 0 | 0 | 없음 | 761ms | 2.5ms | 300 | 0 | {FAILED=300} | 100 → 400 |
| A08 | PAYMENT_UNKNOWN | CANCELED | 300 | 300 | 0 | 0 | 없음 | 778ms | 2.6ms | 300 | 0 | {FAILED=300} | 100 → 400 |
| A09 | COMPENSATION_FAILED_MISMATCH | CANCELED | 300 | 300 | 0 | 0 | 없음 | 715ms | 2.4ms | 300 | 0 | {FAILED=300} | 100 → 400 |
| A10 | COMPENSATION_FAILED_MISMATCH | DONE 금액 다름, 재취소 성공 | 300 | 300 | 0 | 0 | 없음 | 711ms | 2.4ms | 300 | 300 | {FAILED=300} | 100 → 400 |
| A11 | COMPENSATION_FAILED_MISMATCH | DONE 금액 다름, 재취소 타임아웃 | 3 | 0 | 0 | 3 | 있음 | 7ms | 2.3ms | 3 | 3 | {PAYMENT_UNKNOWN=300} | 100 → 100 |
| A12 | COMPENSATION_FAILED_SAME | DONE 금액 같음 | 300 | 300 | 0 | 0 | 없음 | 670ms | 2.2ms | 300 | 0 | {PAID=300} | 100 → 100 |
| A13 | PAYMENT_UNKNOWN | 조회 타임아웃 | 3 | 0 | 0 | 3 | 있음 | 6ms | 2.0ms | 3 | 0 | {PAYMENT_UNKNOWN=300} | 100 → 100 |
| A14 | PAYMENT_UNKNOWN | 조회 거절(404) | 300 | 0 | 300 | 0 | 없음 | 304ms | 1.0ms | 300 | 0 | {PAYMENT_UNKNOWN=300} | 100 → 100 |
| A15 | PAYMENT_UNKNOWN | WAITING_FOR_DEPOSIT | 300 | 0 | 300 | 0 | 없음 | 304ms | 1.0ms | 300 | 0 | {PAYMENT_UNKNOWN=300} | 100 → 100 |
| A16 | PAYMENT_UNKNOWN | PARTIAL_CANCELED | 300 | 0 | 300 | 0 | 없음 | 315ms | 1.1ms | 300 | 0 | {PAYMENT_UNKNOWN=300} | 100 → 100 |
| C01 | CANCEL_UNKNOWN | CANCELED | 300 | 300 | 0 | 0 | 없음 | 776ms | 2.6ms | 300 | 0 | {CANCELED=300} | 100 → 400 |
| C02 | AGED_CANCELING | CANCELED | 300 | 300 | 0 | 0 | 없음 | 918ms | 3.1ms | 300 | 0 | {CANCELED=300} | 100 → 400 |
| C03 | CANCEL_UNKNOWN | DONE | 300 | 300 | 0 | 0 | 없음 | 678ms | 2.3ms | 300 | 0 | {PAID=300} | 100 → 100 |
| C04 | CANCEL_UNKNOWN | NOT_FOUND_PAYMENT | 300 | 300 | 0 | 0 | 없음 | 598ms | 2.0ms | 300 | 0 | {CANCEL_REVIEW=300} | 100 → 100 |
| C05 | CANCEL_UNKNOWN | 조회 타임아웃 | 3 | 0 | 0 | 3 | 있음 | 8ms | 2.7ms | 3 | 0 | {CANCEL_UNKNOWN=300} | 100 → 100 |
| C06 | CANCEL_UNKNOWN | 조회 거절(404) | 300 | 0 | 300 | 0 | 없음 | 305ms | 1.0ms | 300 | 0 | {CANCEL_UNKNOWN=300} | 100 → 100 |
| C07 | CANCEL_UNKNOWN | ABORTED | 300 | 0 | 300 | 0 | 없음 | 305ms | 1.0ms | 300 | 0 | {CANCEL_UNKNOWN=300} | 100 → 100 |

- 상태를 바꾸는 케이스는 건당 2.0~3.1ms다. 재고 복구(상품 행 락)가 붙는 `FAILED`(A03~A10)와 붙지 않는 `PAID`(A01, A02, A12)가 같은 범위에 있다. **앞의 F0(3.0ms)와 T0(2.4ms)의 차이 0.6ms는 같은 조건의 A03(2.4ms)에서 다시 나오지 않았다. 측정 오차였고, "쿼리 2개 × 0.3ms"라는 해석은 근거가 없다.**
- 바꾸지 않고 건너뛰는 케이스(A14~A16, C06, C07)는 건당 약 1.0ms다. 락·쓰기 트랜잭션이 없기 때문이다.
- 회차를 멈추는 케이스(A11, A13, C05)는 3건 처리 후 멈춘다.
- 재취소가 붙는 A10도 건당 2.4ms다. 가짜 PG가 바로 응답하므로 PG를 두 번 부르는 비용이 드러나지 않는다. 운영(초당 5)에서는 속도 제한이 대상 한 건마다 걸리므로 처리량은 다른 케이스와 같다.
- 모두 각 1회 측정이다.

## 케이스별 한 회차 — 운영 설정 (2026-11-26)

같은 측정 코드를 운영 설정으로 돌렸다.
`./gradlew measurementTest --tests "*ReconciliationCaseRoundMeasurementTest" -Dmeasure.count=50 -Dmeasure.perSecond=5 -Dmeasure.pgDelayMs=100 -i`
조건: 대상 50건, 6분 경과, 수량 1, **초당 5**, **PG 지연 100ms**, 회차 상한 50초, 묶음 500, 연속 실패 3. PG 지연 100ms는 실제 토스 값이 아니라, 200ms(호출 간격)보다 짧으면 결과가 같아서(T2) 고른 값이다. 23개 모두 통과(상태·재고·회차 멈춤이 예상과 같음, 반영 오류 0). 전체 3분 42초.

| 코드 | 대상 | 조회 결과 | 대상 수 | 확정 | 건너뜀 | PG 실패 | 남은 대상 | 소요 | 건당 | PG 조회 | PG 취소 | 주문 상태 | 재고 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A01 | PAYMENT_UNKNOWN | DONE 금액 같음 | 50 | 50 | 0 | 0 | 없음 | 9944ms | 198.9ms | 50 | 0 | {PAID=50} | 100 → 100 |
| A02 | AGED_PAYING | DONE 금액 같음 | 50 | 50 | 0 | 0 | 없음 | 9929ms | 198.6ms | 50 | 0 | {PAID=50} | 100 → 100 |
| A03 | PAYMENT_UNKNOWN | ABORTED | 50 | 50 | 0 | 0 | 없음 | 9932ms | 198.6ms | 50 | 0 | {FAILED=50} | 100 → 150 |
| A04 | PAYMENT_UNKNOWN | EXPIRED | 50 | 50 | 0 | 0 | 없음 | 9931ms | 198.6ms | 50 | 0 | {FAILED=50} | 100 → 150 |
| A05 | PAYMENT_UNKNOWN | READY | 50 | 50 | 0 | 0 | 없음 | 9931ms | 198.6ms | 50 | 0 | {FAILED=50} | 100 → 150 |
| A06 | PAYMENT_UNKNOWN | IN_PROGRESS | 50 | 50 | 0 | 0 | 없음 | 9929ms | 198.6ms | 50 | 0 | {FAILED=50} | 100 → 150 |
| A07 | PAYMENT_UNKNOWN | NOT_FOUND_PAYMENT | 50 | 50 | 0 | 0 | 없음 | 9932ms | 198.6ms | 50 | 0 | {FAILED=50} | 100 → 150 |
| A08 | PAYMENT_UNKNOWN | CANCELED | 50 | 50 | 0 | 0 | 없음 | 9924ms | 198.5ms | 50 | 0 | {FAILED=50} | 100 → 150 |
| A09 | COMPENSATION_FAILED_MISMATCH | CANCELED | 50 | 50 | 0 | 0 | 없음 | 9924ms | 198.5ms | 50 | 0 | {FAILED=50} | 100 → 150 |
| A10 | COMPENSATION_FAILED_MISMATCH | DONE 금액 다름, 재취소 성공 | 50 | 50 | 0 | 0 | 없음 | 11222ms | 224.4ms | 50 | 50 | {FAILED=50} | 100 → 150 |
| A11 | COMPENSATION_FAILED_MISMATCH | DONE 금액 다름, 재취소 타임아웃 | 3 | 0 | 0 | 3 | 있음 | 646ms | 215.3ms | 3 | 3 | {PAYMENT_UNKNOWN=50} | 100 → 100 |
| A12 | COMPENSATION_FAILED_SAME | DONE 금액 같음 | 50 | 50 | 0 | 0 | 없음 | 9925ms | 198.5ms | 50 | 0 | {PAID=50} | 100 → 100 |
| A13 | PAYMENT_UNKNOWN | 조회 타임아웃 | 3 | 0 | 0 | 3 | 있음 | 506ms | 168.7ms | 3 | 0 | {PAYMENT_UNKNOWN=50} | 100 → 100 |
| A14 | PAYMENT_UNKNOWN | 조회 거절(404) | 50 | 0 | 50 | 0 | 없음 | 9908ms | 198.2ms | 50 | 0 | {PAYMENT_UNKNOWN=50} | 100 → 100 |
| A15 | PAYMENT_UNKNOWN | WAITING_FOR_DEPOSIT | 50 | 0 | 50 | 0 | 없음 | 9910ms | 198.2ms | 50 | 0 | {PAYMENT_UNKNOWN=50} | 100 → 100 |
| A16 | PAYMENT_UNKNOWN | PARTIAL_CANCELED | 50 | 0 | 50 | 0 | 없음 | 9908ms | 198.2ms | 50 | 0 | {PAYMENT_UNKNOWN=50} | 100 → 100 |
| C01 | CANCEL_UNKNOWN | CANCELED | 50 | 50 | 0 | 0 | 없음 | 9919ms | 198.4ms | 50 | 0 | {CANCELED=50} | 100 → 150 |
| C02 | AGED_CANCELING | CANCELED | 50 | 50 | 0 | 0 | 없음 | 9929ms | 198.6ms | 50 | 0 | {CANCELED=50} | 100 → 150 |
| C03 | CANCEL_UNKNOWN | DONE | 50 | 50 | 0 | 0 | 없음 | 9924ms | 198.5ms | 50 | 0 | {PAID=50} | 100 → 100 |
| C04 | CANCEL_UNKNOWN | NOT_FOUND_PAYMENT | 50 | 50 | 0 | 0 | 없음 | 9922ms | 198.4ms | 50 | 0 | {CANCEL_REVIEW=50} | 100 → 100 |
| C05 | CANCEL_UNKNOWN | 조회 타임아웃 | 3 | 0 | 0 | 3 | 있음 | 512ms | 170.7ms | 3 | 0 | {CANCEL_UNKNOWN=50} | 100 → 100 |
| C06 | CANCEL_UNKNOWN | 조회 거절(404) | 50 | 0 | 50 | 0 | 없음 | 9910ms | 198.2ms | 50 | 0 | {CANCEL_UNKNOWN=50} | 100 → 100 |
| C07 | CANCEL_UNKNOWN | ABORTED | 50 | 0 | 50 | 0 | 없음 | 9908ms | 198.2ms | 50 | 0 | {CANCEL_UNKNOWN=50} | 100 → 100 |

- **확정·건너뜀 모두 건당 약 198ms로 같다.** 속도 제한 간격(200ms)이 건당 시간을 정하고, 그 안에 PG 지연 100ms와 DB 2~3ms가 들어간다. 50건이 약 9.9초이므로 50초 회차에 약 250건이 처리된다(T1과 같음).
- **건너뛰는 케이스도 PG 호출을 한 번 쓰므로 같은 시간이 든다.** 회차는 멈추지 않고 끝까지 돈다.
- **멈추는 케이스는 3건, 약 0.5초 만에 회차가 끝난다.** 나머지 47건은 다음 회차로 넘어간다.
- **A10만 건당 224ms다.** 조회와 재취소로 PG를 두 번(각 100ms) 부르므로 호출 간격 200ms를 넘는다. 앞 절에서 "운영에서는 처리량이 다른 케이스와 같다"고 적은 것은 틀렸다. 이 케이스는 토스가 다른 금액으로 승인해야 생기므로 현실에서는 생기지 않는다.
- 모두 각 1회 측정이다. 서버를 띄운 스케줄러 동작이 아니라, 스케줄러가 부르는 메서드를 테스트에서 직접 부른 결과다.

## 측정 뒤 정할 것 (아직 정하지 않음)
- "묶음 500 / 50초 / 초당 5" 조합을 바꿀지 (묶음을 250에 맞출지, 속도 제한을 올릴지 등). 토스 조회 한도가 확인되지 않아 값은 그대로 둔다. 운영 회차 로그를 보고 조정한다.

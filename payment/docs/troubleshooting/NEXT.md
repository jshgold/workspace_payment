# 트러블슈팅 이어서 하기 (작업 인계)

> 마지막 갱신: 2026-11-19. 새 세션은 이 파일부터 읽고 시작한다.
> 이 작업은 phase-04 체크리스트 밖의 트러블슈팅이다. phase-04 다음 작업(4-3 취소 연동)은 아직 시작하지 않았다.

## 1. 진행 원칙 (사용자와 합의한 것)

- 현재 결제 구조를 **"설계 실패 버전"**으로 보고, 문제를 **재현·측정한 뒤** 개선한다.
- **개선(설계 변경)은 사용자가 지시하기 전까지 적용하지 않는다.** 예: 재고 차감을 `startPaying`으로 옮기기, 멱등키를 `Payment` 컬럼으로 옮기기.
- **현재 버전을 검증하기 위한 계측은 추가해도 된다.** 로그, 카운터, 측정 전용 엔드포인트, 테스트 대역 설정 등. 비즈니스 흐름은 바꾸지 않는다.
- 재설계안(`docs/payment-redesign-discussion.md`)은 별개의 수정이다. 트러블슈팅 문서에 섞지 않는다.
- 측정 결과가 예상과 다르면 그대로 기록하고, 앞선 해석을 고친다. (확인한 것과 추정을 구분해 적는다)

## 2. 문서 현황

| 문서 | 상태 | 요약 |
|---|---|---|
| `payment-stock-deduction-timing.md` | **현재 구조 측정 완료, 개선 미적용** | 재고를 PG 승인 뒤에 차감 → 재고 1에 N건이면 PG 승인 N, 취소 N-1. JUnit·k6·DB로 확인. 스레드 고갈은 해피 케이스에서도 같은 수준이라 **경합 탓으로 볼 수 없다**고 결론을 고쳤다. |
| `payment-transaction-granularity.md` | **현재 구조 측정 완료, 개선 미적용** (병목 = 커넥션 풀, 2-7) | 단건 실측 완료: 이긴 요청 트랜잭션 4/SQL 12, 진 요청 트랜잭션 5/SQL 12, 주문 행 `FOR UPDATE` 3/4번. 진 요청도 Payment INSERT 후 롤백함(IDENTITY). 해피 케이스가 계산상 1.5초여야 하는데 3.5초 걸린 원인은 아직 미확인. |
| `product-list-pagination.md` | 완료 (phase-01) | 이번 작업과 무관 |

아직 문서로 만들지 않은 후보 (이전 세션에서 정리만 함):
- 결제가 됐는지 아무도 모르는 주문 (PG 타임아웃 → `PAYING` + 멱등키 `IN_PROGRESS`, `Payment` 기록 없음). 가짜 PG에 "승인 후 타임아웃" 시나리오가 없어 재현하려면 추가가 필요하다.
- PG 호출 전 실패(락 타임아웃 등)인데 멱등키가 영구 `IN_PROGRESS`로 남는 문제.

## 3. 다음 작업: 개선 전에 DB 쿼리·대기 시간 확인

`payment-transaction-granularity.md` 6번 체크리스트 앞 항목들이다. 재고 차감 시점 개선의 **선행 작업**이기도 하다.
(개선하면 상품 행 `FOR UPDATE`가 PG 호출 앞으로 옮겨져, 1000건이 PG 전에 상품 행 하나에 줄을 선다. 지금의 락·커넥션 대기를 모르면 개선 후 결과를 해석할 수 없다.)

1. ~~단건 SQL 순서 기록~~ — 완료 (2026-11-19, 문서 2-1)
2. ~~요청당 트랜잭션·커넥션 수~~ — 완료 (2026-11-19, 문서 2-1)
   - Hibernate 7은 `generate_statistics=true`만으로는 안 찍힌다. `--logging.level.org.hibernate.session.metrics=debug`가 필요하다. 트랜잭션 경계는 `--logging.level.org.springframework.orm.jpa.JpaTransactionManager=debug`로 본다.
2-1. ~~단건 구간별(DB / PG) 소요 시간 기준값~~ — 완료 (2026-11-19, 문서 2-1-1). 이긴 요청 DB 45ms + PG 304ms, 진 요청 DB 65ms + PG 609ms.
2-2. ~~200건 / 1000건 동시 해피 케이스 (결제만)~~ — 완료 (2026-11-19, 문서 2-4 ~ 2-6). 요청 1건이 스레드를 쥐는 시간 0.35초 → 0.66초(DB 구간 45 → 약 360ms), 처리량 한계 약 300건/초. 1000건의 고객 체감 2초는 대부분 스레드 줄서기.
3. ~~부하 중 대기 시간~~ — 완료 (2026-11-19, 문서 2-7). 200건에서 DB 구간 376ms 중 커넥션 대기 342ms, COMMIT 8ms, 나머지 26ms. COMMIT·상품 행 락 대기는 늘지 않았다. **병목은 커넥션 풀이고, 비용은 요청당 트랜잭션 수에 비례한다.**
4. **다음 작업: 미정** — 측정은 끝났다. 개선 방식 결정(문서 5번 표, `payment-redesign-discussion.md`)은 사용자 지시를 기다린다. — 해피 케이스(재고 1000, 주문 1000, PG 지연 300ms)를 다시 돌리며 Hikari 커넥션 대기와 상품 행 락 대기를 계측. 계측 방법은 아직 정하지 않았다. (Hikari 메트릭/로그, `performance_schema.data_lock_waits` 등) → 정하기 전에 사용자에게 제안하고 확인받는다.

## 4. 실행 방법

**서버 (가짜 PG, local 프로필)**
```
./gradlew bootRun --console=plain --args='<옵션>'
```
| 옵션 | 용도 |
|---|---|
| `--logging.level.org.hibernate.SQL=info --logging.level.org.hibernate.orm.jdbc.bind=info` | 부하 측정 시 SQL 로그 끄기 (출력 자체가 응답 시간을 왜곡) |
| `--app.pg.fake.delay=300ms` | 가짜 PG 승인·취소 지연 |
| `--server.tomcat.threads.max=20` | 스레드 고갈을 작은 규모로 재현 |

- 기동 후 확인: 로그에 `The following 1 profile is active: "local"`, 그리고 `GET /api/fake-pg/stats`가 200이면 가짜 PG가 주입된 것이다. (토스 모드면 이 엔드포인트가 없다)
- `bootRun`은 `.env`를 환경변수로 주입한다. `.env`의 `PROFILE_SETTING`이 비어 있으면 프로필이 없이 떠서 기동에 실패한다. (2026-11-19에 한 번 발생, 사용자가 수정함) `.env`는 읽지 않는다.
- 서버를 다시 띄울 때는 `lsof -nP -iTCP:8080 -sTCP:LISTEN -t`로 PID를 찾아 `kill <PID>`(SIGTERM)로 내린다.

**k6**
```
k6 run k6/stock-contention.js                       # 재고 경합, PG 호출 횟수 출력 (-e ORDERS=, -e STOCK=)
k6 run --out json=<파일> -e ORDERS=1000 -e STOCK=1000 -e DURATION=25 k6/thread-starvation.js > <로그> 2>&1
python3 -I k6/analyze_starvation.py <json 파일> <로그 파일>   # 상품 조회를 결제 전/중/후로 나눠 요약
```

**측정용 계측 (현재 코드에 있음)**
- `FakePaymentClient`: `app.pg.fake.delay` 프로퍼티로 초기 지연 설정 (문자열로 받아 `DurationStyle`로 파싱. `Duration`으로 받으면 `PaymentClientSwitchTest`의 최소 컨텍스트에서 변환 실패함)
- `FakePgController`: fake 모드 전용 `GET /api/fake-pg/stats`, `POST /api/fake-pg/reset`
- `PaymentSegmentTimer`: 결제 승인 1건의 구간별 소요 시간(`begin`, `startPaying`, `pgApprove`, `complete`, `pgCancel`, `revertToPending`, `succeed`/`fail`)을 요청당 한 줄로 남긴다. `--logging.level.payment.timing=debug`로 켜고, 꺼져 있으면 시간을 재지 않는다. 부하 측정에서는 SQL 로그를 끄고 이것만 켠다.
- `JdbcTimingDataSource` / `JdbcTiming` / `JdbcTimingConfig` (`common` 패키지): `payment.timing`이 DEBUG일 때만 DataSource를 감싸, DB 구간마다 커넥션 대기와 COMMIT 시간을 `complete=103.3ms(conn=85.6ms,commit=1.2ms)` 형식으로 붙인다.
- JUnit 재현 테스트: `PaymentFlowTestSupport.stockContentionCausesApproveThenCancel()` (현재 구조의 문제를 기대값으로 고정. 개선 후 PG 승인 1/취소 0으로 바꾸는 것이 개선의 증거)

## 5. 환경 메모

- pre-push hook이 `./gradlew test` 전체를 돌린다. 메인/테스트 코드를 바꿨으면 푸시 전에 관련 테스트를 넓게 돌릴 것. (계측 코드를 바꿨을 때 `PaymentClientSwitchTest`를 빠뜨려 푸시가 막힌 적 있음)
- 로컬 `payment` DB에 k6 데이터가 남아 있다: 상품 6개(`k6-` 접두어, ID 2~7), 주문 2,710건, 결제 1,005건. 단건 측정용으로 상품 6개(`tx-granularity-probe*`, `tx-timing-probe-*`, ID 8~13), 주문 12건(2716~2727)이 추가됐다. 200건·1000건 해피 케이스로 상품 2개(`k6-contention-*`, ID 14~15)와 주문 1,200건이 더 추가됐다. 지우려면 DELETE가 필요하므로 사용자 승인을 받는다.
- 측정 원본(JSON·로그)은 이전 세션의 scratchpad(임시 폴더)에 있었고 남아 있지 않을 수 있다. 결과 수치는 문서에 모두 옮겨 두었다.

# 결제 승인: 요청 1건이 트랜잭션을 너무 잘게 나눠 여는 문제

> 상태: **미착수** (phase-04 진행 중, `payment-stock-deduction-timing.md` k6 측정 중에 발견한 별도 문제)
> 아래 "문제"는 코드를 읽고 센 것이다. SQL 로그, Hibernate 통계, Hikari 대기 시간으로 아직 확인하지 않았다. 측정 후 "결과" 섹션을 채운다.

---

## 1. 배경

결제 승인은 아래 순서로 호출된다. 각 Service 메서드가 `@Transactional`이라 호출마다 커넥션을 빌리고 BEGIN/COMMIT을 한다.

```
PaymentApprovalFacade.approve()        (트랜잭션 없음)
 ├ IdempotencyService.begin()          트랜잭션
 ├ PaymentFacade.approve()             (트랜잭션 없음)
 │  ├ PaymentStateService.startPaying()   트랜잭션
 │  ├ PG 승인 호출                         (트랜잭션 밖)
 │  ├ PaymentStateService.complete()      트랜잭션
 │  └ (실패 시) PG 취소 → revertToPending()  트랜잭션
 └ IdempotencyService.succeed() / fail()  트랜잭션
```

PG 호출을 트랜잭션 밖으로 빼는 것(ARCHITECTURE 4, 5-2)은 의도한 설계다.
문제는 **PG 호출과 상관없는 구간까지** 트랜잭션이 나뉘어 있다는 점이다.

## 2. 문제 (가설)

### 2-1. 요청 1건당 트랜잭션 4~5번 (비관적 락 구현 기준, 코드로 센 값)

| # | 위치 | 하는 일 | 이긴 요청 | 경합에서 진 요청 |
|---|---|---|---|---|
| 1 | `IdempotencyService.begin` | 주문 `FOR UPDATE`, 키 조회, 키 INSERT | O | O |
| 2 | `startPaying` | 주문 `FOR UPDATE`, 상태 UPDATE | O | O |
| | PG 승인 | | | |
| 3 | `complete` | 주문 `FOR UPDATE`, Payment INSERT, 상품 `FOR UPDATE`, 재고 UPDATE | 커밋 | **롤백** |
| | PG 취소 | | | 진 요청만 |
| 4 | `revertToPending` | 주문 `FOR UPDATE`, 상태 UPDATE | | O |
| 5 | `IdempotencyService.succeed` / `fail` | 키 조회, 상태 UPDATE | O | O |
| | **합계** | | **4번** | **5번** |

- 원자적 쿼리 구현(`AtomicUpdatePaymentStateService`)은 `FOR UPDATE` 대신 조건부 UPDATE를 쓰지만 트랜잭션 수는 같다.
- 3번 롤백과 4번은 재고 판정 시점 문제(`payment-stock-deduction-timing.md`)에서 오는 낭비라 이 문서에서는 따로 다루지 않는다.

### 2-2. 같은 주문 행을 여러 번 잠근다
- 주문 행 `FOR UPDATE`가 요청 1건에 3~4번 일어난다.
- 1번(`begin`)과 2번(`startPaying`)은 PG 호출 전에 연달아 일어나는데도 트랜잭션이 나뉘어 있다. 둘 다 "이 주문을 내가 처리한다"를 확인하는 관문이다.
- 두 관문 사이에 틈이 있어, 1번 커밋 후 2번 전에 실패하면 멱등키만 `IN_PROGRESS`로 남는다. (락 타임아웃 등. PG는 호출되지 않았는데 재요청이 계속 "처리 중"을 받는다)

### 2-3. 커넥션 풀 경합
- Hikari 기본 풀 크기는 10이다. (설정 파일에 별도 지정 없음)
- 100건이 동시에 들어오면 커넥션 10개를 놓고 400~500번 빌리기 경쟁을 한다.
- `payment-stock-deduction-timing.md` k6 측정(100건, PG 지연 300ms)에서 실패 응답이 PG 왕복(600ms)보다 약 130~230ms 더 걸렸다. 이 차이의 유력한 후보가 커넥션 대기와 주문 행 락 대기다. **확인하지 않은 추정이다.**

## 3. 원인 (추정)

- "PG 호출은 트랜잭션 밖"이라는 원칙을 지키려고 Facade에서 Service 메서드를 나눴는데, 나누는 기준이 **PG 호출 경계**가 아니라 **기능 단위(멱등키 / 주문 상태 / 결제 반영)**가 됐다.
- 멱등키가 별도 테이블·별도 Service라서, 주문 선점과 같은 트랜잭션에 넣기 어려운 구조다.

## 4. 측정 계획 (현재 버전 계측만, 개선 적용 없음)

| 항목 | 방법 |
|---|---|
| 요청 1건당 트랜잭션·커넥션 획득 수 | `spring.jpa.properties.hibernate.generate_statistics=true` → 세션별 "JDBC connections acquired", "JDBC statements executed" 로그 |
| 요청 1건당 SQL 목록 | `org.hibernate.SQL=debug`로 이긴 요청 1건, 진 요청 1건을 각각 단건 실행해 순서 기록 |
| 커넥션 대기 시간 | Hikari 대기 시간 계측 (로그 레벨 조정 또는 측정용 로그 추가) |
| 주문 행 락 대기 | 위 SQL 로그 타임스탬프 또는 `performance_schema.data_lock_waits` |
| 부하 시 영향 | `k6/stock-contention.js` 재사용 (100건, PG 지연 300ms), 2-1 표를 실측값으로 교체 |

- 부하 측정은 `--logging.level.org.hibernate.SQL=info`로 SQL 로그를 끈다. 단건 SQL 기록 때만 켠다.

## 5. 개선 후보 (적용 전 검토용)

| 방법 | 내용 | 대가 / 관련 |
|---|---|---|
| 멱등키 선점 + 주문 선점을 한 트랜잭션으로 | 1번과 2번을 합친다. 2-2의 틈도 사라진다 | `payment-redesign-discussion.md`의 "멱등키를 `Payment` 컬럼으로" 결정과 직결 |
| 결과 기록을 반영 트랜잭션에 포함 | 3번 커밋과 5번(`succeed`)을 합친다 | 멱등키 위치가 바뀌어야 자연스럽다 |
| 실패 기록을 상태 복구에 포함 | 4번과 5번(`fail`)을 합친다 | 같음 |
| 커넥션 풀 크기 조정 | 근본 해결은 아니고 증상 완화 | 트랜잭션 수가 그대로면 경합 지점만 옮겨간다 |

## 6. 작업 체크리스트

- [ ] 단건 실행으로 이긴 요청 / 진 요청의 SQL·트랜잭션 순서 기록
- [ ] Hibernate 통계로 요청당 커넥션 획득 수 실측
- [ ] Hikari 대기 시간 계측 방법 결정
- [ ] k6(100건, 지연 300ms)로 커넥션 대기가 130~230ms 차이의 원인인지 확인
- [ ] 개선 방식 결정 (재설계 논의와 함께)
- [ ] 개선 후 같은 측정으로 비교, 결과 기록

## 7. 결과 (측정 후 작성)

| 항목 | 현재 구조 | 개선 후 |
|---|---|---|
| 이긴 요청: 트랜잭션 / 쿼리 수 | | |
| 진 요청: 트랜잭션 / 쿼리 수 | | |
| 커넥션 대기 시간 (100건 동시) | | |
| 실패 응답 시간 (100건, 지연 300ms) | | |

## 8. 학습 포인트

- 트랜잭션을 나누는 기준은 "외부 I/O 경계"다. 기능 단위로 나누면 PG 호출과 상관없는 구간까지 쪼개진다.
- 트랜잭션 하나하나는 짧아도, 횟수가 많으면 커넥션 빌리기·락 획득·커밋 왕복이 쌓인다.
- 같은 목적의 관문(멱등키 선점, 주문 선점)이 두 트랜잭션에 나뉘면 그 사이에 틈이 생긴다.

# Architecture & System Design Rules

## 1. 패키지 구조 (Domain-Driven Packaging)
기능/도메인 단위로 패키지를 묶고, 각 도메인 내부는 아래 계층을 유지한다.
- `controller`: API 엔드포인트, 요청 검증(`@Valid`), 응답 매핑
- `facade`: 다중 도메인 서비스 오케스트레이션, 외부 I/O와 DB 트랜잭션의 분리가 필요할 때 사용
- `service`: 핵심 비즈니스 흐름 제어 및 트랜잭션 경계(`@Transactional`)
- `domain`: JPA Entity, 도메인 모델, 도메인 비즈니스 로직, Repository 인터페이스
- `infrastructure`: QueryDSL 구현체(`*RepositoryCustomImpl`), Redis/Kafka/S3/외부 API 클라이언트 등 기술 구현체
- `dto`: 요청(`*Request`) 및 응답(`*Response`) 데이터 전송 객체

## 2. 단방향 의존성 및 DTO 변환 경계
- 의존성 방향은 **`Controller` -> (`Facade`) -> `Service` -> `Domain / Repository`** 순서를 따른다.
- **Controller -> Repository 직접 호출 금지:** 단순 조회라도 Service(또는 QueryService)를 거친다.
- **순환 참조 금지:** 두 도메인의 조합이 필요하면 상위에 `Facade`를 만들어 해결한다.
- **Entity 노출 금지:** JPA Entity를 Controller로 노출하지 않는다. Controller는 `record`로 작성된 Request/Response DTO만 입출력으로 다룬다.
- **필드 주입 금지:** `@Autowired` 필드 주입 대신 `@RequiredArgsConstructor` 생성자 주입만 사용한다.

## 3. OSIV 비활성화 전제 (`open-in-view: false`)
- 이 프로젝트는 DB 커넥션 고갈 방지를 위해 OSIV 비활성화를 전제로 한다.
- 지연 로딩 프록시 초기화와 Entity -> DTO 변환은 `@Transactional`이 선언된 Service 계층 안에서 끝낸다.
- Controller에서 지연 로딩 필드에 접근해 `LazyInitializationException`이 나는 코드를 작성하지 않는다.

## 4. 트랜잭션 경계 및 `REQUIRES_NEW` 금지
- 조회 전용 메서드에는 `@Transactional(readOnly = true)`, 변경(CUD) 메서드에만 `@Transactional`을 선언한다.
- **`Propagation.REQUIRES_NEW`는 사용하지 않는다.**
  한 스레드가 커넥션 풀에서 커넥션 2개를 동시에 점유해 풀 데드락을 유발할 수 있기 때문이다.
  트랜잭션 분리가 필요하면 아래 둘 중 하나를 사용한다.
  1. **이벤트 기반 분리:** `ApplicationEventPublisher`로 이벤트를 발행하고, `@TransactionalEventListener(phase = AFTER_COMMIT)`(실패 로그는 `AFTER_ROLLBACK`)과 `@Async`를 조합해 부모 커넥션 반납 후 실행되게 한다.
  2. **Facade 분리:** 트랜잭션이 없는 Facade에서 각 `@Transactional` 서비스 메서드를 순차 호출해 커넥션 점유 시점을 나눈다.

## 5. 이중 쓰기(Dual-Write) 금지 및 외부 미들웨어 분리
RDB와 외부 미들웨어(Redis, Kafka, Elasticsearch, S3, 외부 API 등)에 함께 기록해야 할 때, 하나의 `@Transactional` 메서드 안에서 동시에 쓰지 않는다. 아래 3가지 패턴 중 하나로 분리한다.

1. **DB 커밋 후 미들웨어 반영 (`AFTER_COMMIT` 이벤트):**
   - 캐시 무효화/갱신, 검색 인덱싱, Kafka 발행, 알림 발송은 도메인 이벤트를 발행하고 `@TransactionalEventListener(phase = AFTER_COMMIT)`에서 처리한다.
   - **리스너에서 DB 쓰기가 필요하면 반드시 `@Async`를 함께 붙인다.** `AFTER_COMMIT` 시점에는 기존 트랜잭션 리소스가 아직 묶여 있어서, `@Async` 없이 `REQUIRED`로 쓰면 커밋되지 않고 조용히 유실된다. (`REQUIRES_NEW`는 4번에서 금지했으므로 `@Async`로 해결한다.)
   - `@Async` 사용 시 `@EnableAsync`와 전용 `ThreadPoolTaskExecutor` 설정을 함께 둔다. 기본 executor에 맡기지 않는다.
2. **선(先) 외부 I/O -> 후(後) DB 트랜잭션 (Facade 분리):**
   - S3/MinIO 업로드나 외부 결제/인증 API 호출이 먼저 필요하면, 트랜잭션이 없는 Facade에서 외부 I/O를 끝낸 뒤 결과값만 넘겨 짧은 `@Transactional` 서비스 메서드를 호출한다.
   - 이후 DB 저장이 실패하면 보상 작업(업로드 파일 삭제, 결제 취소 등)을 수행한다.
3. **Transactional Outbox (데이터 유실이 허용되지 않을 때):**
   - 금융/결제/주문처럼 원자성이 반드시 보장되어야 하면, 같은 트랜잭션에서 `Outbox` 엔티티를 함께 INSERT하고 별도 워커/스케줄러가 미들웨어로 전달하는 구조를 제안한다.

## 6. 3단계 다중 계층 제약조건
데이터 생성/변경 기능을 구현할 때는 아래 세 방어선을 모두 코드로 구현한다.
1. **Presentation (API 입력 경계):** Request DTO(`record`) 필드에 Bean Validation(`@NotBlank`, `@NotNull`, `@Size`, `@Positive` 등)을 명시하고 Controller 파라미터에 `@Valid`를 적용한다.
2. **Domain (비즈니스 불변식):** Entity의 정적 팩토리 메서드나 도메인 메서드 안에서 도메인 규칙을 한 번 더 검증(Guard Clause)한다.
3. **Persistence (물리적 무결성):** `@Column(nullable = false, length = ...)`를 명시하고, 중복 불가 키는 `@Table(uniqueConstraints = @UniqueConstraint(name = "uk_...", columnNames = {...}))`로 정의한다.

## 7. 동시성 제어 및 멱등성 — 구현 전 먼저 제안
재고 차감, 선착순 발급, 잔액 변경처럼 동시성 이슈가 있거나 중복 요청 방지가 필요한 기능은 방식을 임의로 정하지 않는다.

1. **금지:**
   - `synchronized`나 `ReentrantLock`으로 DB 데이터 동시성을 제어하지 않는다.
   - `existsBy...` 조회만으로 동시 중복 생성을 막으려 하지 않는다.
2. **구현 전 제안:** 사용자가 방식을 정하지 않았다면 코딩 전에 아래 기준으로 적합한 방식 1~2가지를 제안하고 확인받는다.
   - **충돌 확률 낮음 (일반 정보/게시글 수정):** `@Version` 기반 낙관적 락
   - **충돌 확률 높음 + 강한 정합성 (재고/잔액, 단일 DB):** `@Lock(PESSIMISTIC_WRITE)` 비관적 락 또는 조건부 Atomic Update 쿼리
   - **대용량 트래픽 또는 외부 연동 포함 (선착순 이벤트/결제):** Redisson 분산 락. 반드시 `락 획득 -> 트랜잭션 시작 -> 비즈니스 로직 -> 커밋 -> 락 해제` 순서가 보장되도록 AOP 또는 Facade로 분리한다.
   - **API 멱등성:** 도메인상 1회만 가능한 작업은 DB 복합 Unique 제약으로 막고, 반복 가능한 요청의 중복 전송은 `Idempotency-Key` 헤더 검증 방식을 제안한다.

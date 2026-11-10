# Java & Spring Code Conventions

## 1. Java 21 및 불변성
- 모든 Request/Response DTO와 값 객체(VO)는 Java `record`로 작성한다.
- 컬렉션을 반환할 때는 `null` 대신 빈 컬렉션(`List.of()` 등)을 반환한다.

## 2. JPA Entity 작성 규칙 (Rich Domain Model)
- **기본 어노테이션:** `@Entity`, `@Getter`, `@NoArgsConstructor(access = AccessLevel.PROTECTED)`
- **사용 금지:** `@Setter`, `@Data`, `@AllArgsConstructor`
- **객체 생성:** 생성자에 `@Builder`를 붙이고, 의미 있는 정적 팩토리 메서드(`create(...)`, `of(...)`)로만 생성한다.
- **상태 변경:** Setter 대신 비즈니스 의도가 드러나는 도메인 메서드(`cancelOrder()`, `deductStock(int quantity)`)를 Entity 안에 작성한다.
- **연관관계:** `@ManyToOne`, `@OneToOne`은 모두 `fetch = FetchType.LAZY`로 설정한다. `EAGER`는 사용하지 않는다.

## 3. 데이터 접근, CQRS 및 QueryDSL
- **명령과 조회 분리:**
  - 상태 변경(CUD)은 Entity의 도메인 메서드와 더티 체킹으로 수행한다.
  - 복잡한 화면 조회, 통계, 다중 조인 검색은 QueryDSL로 `record` Response DTO에 직접 프로젝션(`Projections.constructor` 또는 `@QueryProjection`)해서 반환한다.
- 동적 쿼리, 복잡한 조인, 페이징 조회는 `JPAQueryFactory`를 사용해 `*RepositoryCustomImpl`에 구현한다.

## 4. N+1 문제 차단
목록 조회나 연관 엔티티 탐색에서 N+1 쿼리가 나는 코드를 작성하지 않는다.
1. **ToOne (`@ManyToOne`, `@OneToOne`):** 연관 데이터가 필요하면 QueryDSL(또는 JPQL)에서 `fetchJoin()`을 사용한다.
2. **ToMany (`@OneToMany`):**
   - 페이징 쿼리에서는 메모리 페이징을 막기 위해 컬렉션에 `fetchJoin()`을 쓰지 않는다.
   - ToOne만 `fetchJoin()`으로 묶어 페이징하고, 컬렉션은 `default_batch_fetch_size`(또는 `@BatchSize(size = 100)`)로 `IN` 절 배치 조회한다.
3. **반복문 안 쿼리 금지:**
   - `for`문이나 `stream().map()` 안에서 Repository 조회나 초기화되지 않은 Lazy 프록시를 건별로 호출하지 않는다.
   - 루프 전에 `findAllByIdIn(...)` 등으로 한 번에 조회해 `Map`으로 매핑해서 쓴다.

## 5. 예외 처리
- 비즈니스 로직에서 `RuntimeException`, `Exception`을 그대로 던지지 않는다. (도메인/VO 생성 시점의 값 검증은 `IllegalArgumentException`, `IllegalStateException` 허용)
- 프로젝트의 `BusinessException`과 `ErrorCode` Enum으로 명확한 에러 코드를 던진다.
- 예외를 잡고 무시하거나 `e.printStackTrace()`를 호출하지 않는다. `@Slf4j`의 `log.error()` / `log.warn()`을 사용한다.

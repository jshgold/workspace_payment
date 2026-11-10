# Phase 00. 프로젝트 환경 세팅

**목표:** 코드를 쓰기 전에 DB, 스키마 관리, 테스트 환경을 갖춘다.
락 공부의 대부분은 "실제로 어떤 SQL이 나가는지"를 보는 일이라서, SQL 로그와 실제 MySQL 테스트 환경을 처음부터 만들어둔다.

---

- [ ] **0-1. 프로젝트 생성과 의존성**
  - Spring Boot 3.x, Java 21, Gradle
  - 의존성: Web, Data JPA, Validation, MySQL Driver, Lombok, Flyway(+ flyway-mysql), QueryDSL, Testcontainers(MySQL, JUnit5)
  - **완료 조건:** `./gradlew build -x test` 성공
  - **학습 포인트:** QueryDSL은 Spring Boot 3에서 설정 방식(jakarta classifier, Q클래스 생성 위치)이 바뀌었다. 무엇이 바뀌었는지 확인한다.

- [ ] **0-2. docker-compose로 MySQL 실행**
  - MySQL 8.0, `utf8mb4`, 포트 3306, 데이터 볼륨 지정
  - **완료 조건:** `docker compose up -d` 후 접속해서 `SELECT @@transaction_isolation;` 결과 확인
  - **학습 포인트:** MySQL 기본 격리수준은 `REPEATABLE-READ`다. PostgreSQL(`READ COMMITTED`)과 무엇이 다른지 한 줄로 정리해본다.

- [ ] **0-3. application.yml 설정**
  - `open-in-view: false`, `ddl-auto: validate`, Flyway 활성화
  - SQL 로그: Hibernate SQL과 바인딩 파라미터가 보이게 설정
  - `local` 프로필 분리
  - **완료 조건:** 애플리케이션이 뜨고, Flyway 기본 테이블(`flyway_schema_history`)이 생긴다.
  - **학습 포인트:** OSIV를 끄면 어떤 코드가 `LazyInitializationException`을 내는지, 왜 끄는지 설명할 수 있어야 한다.

- [ ] **0-4. 공통 예외 처리**
  - `ErrorCode` enum, `BusinessException`, `@RestControllerAdvice` 기반 `GlobalExceptionHandler`
  - Bean Validation 실패(`MethodArgumentNotValidException`) 응답 형식 통일
  - **완료 조건:** 존재하지 않는 경로나 잘못된 요청에 일관된 JSON 에러 응답이 나온다.

- [ ] **0-5. Testcontainers 통합 테스트 기반**
  - MySQL 컨테이너를 띄우는 공통 테스트 설정 (`@ServiceConnection` 또는 추상 베이스 클래스)
  - 컨텍스트 로딩 테스트 하나 작성
  - **완료 조건:** 테스트가 실제 MySQL 컨테이너에서 통과한다.
  - **학습 포인트:** 왜 H2가 아니라 실제 MySQL로 테스트해야 하는지 (락, 격리수준, SQL 문법 차이)

---

**Phase 완료 후 스스로 답해볼 것**
- OSIV를 끈 상태에서 Entity → DTO 변환은 어디서 해야 하나?
- `ddl-auto: validate`와 Flyway를 같이 쓰면 어떤 이점이 있나?

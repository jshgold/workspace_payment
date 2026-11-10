# Verification Loop & Test Rules

코드를 수정했다면 사용자에게 보고하기 전에 아래 검증 루프를 터미널에서 실행한다.

## 1. 검증 단계

### Step 1: 컴파일 검증 (코드 수정 직후)
```
./gradlew compileJava compileTestJava --console=plain
```

### Step 2: 대상 테스트만 실행
시간과 토큰을 아끼기 위해 전체 테스트(`./gradlew test`)는 돌리지 않는다. 수정하거나 새로 작성한 테스트 클래스만 지정해서 실행한다.
```
./gradlew test --tests "*{클래스명}Test" --console=plain
```
전체 테스트가 필요하다고 판단되면 실행하기 전에 사용자에게 먼저 묻는다.

## 2. 실패 시 처리 (Self-Healing & Circuit Breaker)
Step 1 또는 Step 2가 `BUILD FAILED`를 반환하면:
1. 스택 트레이스에서 컴파일 에러 라인이나 AssertionError의 핵심 원인을 찾는다.
2. 코드를 고치고 Step 1 -> Step 2를 다시 실행한다.
3. **Circuit Breaker:** 같은 에러로 3회 연속 실패하면 루프를 멈춘다. 비즈니스 로직이나 테스트 검증식을 주석 처리하거나 바꿔서 통과시키지 않는다. 에러 원인과 시도 내역을 사용자에게 보고한다.

## 3. 테스트 작성 컨벤션
- **프레임워크:** JUnit 5 + AssertJ (`org.assertj.core.api.Assertions.assertThat`)
- **표기:**
  - `@DisplayName`에 시나리오와 검증 조건을 한글로 구체적으로 적는다.
  - 메서드 안은 `// given`, `// when`, `// then` 주석으로 구간을 나눈다.
- **속도와 격리:**
  - Domain, Service 테스트는 `@SpringBootTest` 대신 `@ExtendWith(MockitoExtension.class)` 기반 순수 단위 테스트로 작성한다.
  - QueryDSL 검증이나 통합 검증이 필요할 때만 `@DataJpaTest` 또는 `@SpringBootTest`를 사용한다.

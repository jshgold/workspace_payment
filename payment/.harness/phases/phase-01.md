# Phase 01. 상품 CRUD

**목표:** 이후 락 실험의 대상이 될 Product를 규칙에 맞게 만든다.
기능 자체는 단순하니, 엔티티 설계(도메인 메서드, 3단계 제약조건)와 조회 쿼리(N+1, 프로젝션)를 제대로 하는 데 집중한다.

---

- [ ] **1-1. Product 엔티티와 테이블**
  - Flyway `V1__create_product.sql` 작성 (이름, 가격, 재고, 상태, `deleted_at`, 생성·수정 시각)
  - 엔티티: 정적 팩토리 `create(...)`, 도메인 메서드 `update(...)`, `changeStatus(...)`, `delete()`
  - Guard Clause: 가격 > 0, 재고 >= 0
  - 도메인 단위 테스트
  - **완료 조건:** 엔티티 단위 테스트 통과, 애플리케이션 기동 시 `validate` 통과
  - **학습 포인트:** 3단계 제약조건(DTO 검증 / 도메인 Guard / DB 제약)이 각각 무엇을 막는지. 하나만 있으면 어떤 구멍이 생기나?

- [ ] **1-2. 상품 등록 API**
  - `POST /api/products`
  - `CreateProductRequest`(record + Bean Validation) → Service → Controller
  - **완료 조건:** 서비스 단위 테스트 통과, 잘못된 요청(가격 0 등)에 400 응답
  - **학습 포인트:** Controller가 Entity를 받거나 반환하면 어떤 문제가 생기나?

- [ ] **1-3. 상품 단건 조회 API**
  - `GET /api/products/{id}`
  - 삭제된 상품은 `PRODUCT_NOT_FOUND`
  - **완료 조건:** 존재/미존재/삭제됨 세 경우 테스트

- [ ] **1-4. 상품 수정 API**
  - `PUT /api/products/{id}` (이름, 가격, 재고)
  - `PATCH /api/products/{id}/status` (판매 상태 변경)
  - **완료 조건:** 수정 후 조회 시 반영 확인, 더티 체킹으로 UPDATE가 나가는지 SQL 로그로 확인
  - **학습 포인트:** `save()`를 호출하지 않았는데 UPDATE가 나가는 이유 (영속성 컨텍스트, 더티 체킹)

- [ ] **1-5. 상품 삭제 API (소프트 삭제)**
  - `DELETE /api/products/{id}` → `deleted_at` 기록
  - **완료 조건:** 삭제 후 단건·목록 조회에서 제외됨
  - **학습 포인트:** 소프트 삭제 시 UNIQUE 제약(예: 상품명)과 충돌하는 문제. 이번 프로젝트에선 어떻게 할지 결정한다.

- [ ] **1-6. 상품 목록 조회 (QueryDSL)**
  - `GET /api/products?keyword=&status=&page=&size=`
  - `ProductRepositoryCustomImpl`에서 동적 조건, 페이징, record DTO 프로젝션
  - `STOPPED`, 삭제된 상품 제외
  - Testcontainers 기반 Repository 테스트
  - **완료 조건:** 조건 조합별 테스트 통과, SQL 로그에서 count 쿼리와 목록 쿼리 확인
  - **학습 포인트:** Entity 조회 후 변환 vs DTO 직접 프로젝션의 차이. 페이징 count 쿼리 최적화.

---

**Phase 완료 후 스스로 답해볼 것**
- `Product.decreaseStock()`을 지금 만든다면, 동시에 100명이 호출할 때 어떤 일이 생길까? (→ phase-02에서 확인)

# 상품 목록 조회: count 쿼리와 offset 페이징 최적화

> 상태: **미착수** (phase-01 1-6 구현 시점에 발견한 개선 후보를 정리한 문서)
> 아래 "가설"은 아직 측정으로 확인하지 않았다. 측정 후 "결과" 섹션을 채운다.

---

## 1. 배경

`GET /api/products?keyword=&status=&page=&size=` 는 `ProductRepositoryCustomImpl.search()` 에서
**목록 쿼리**와 **count 쿼리**를 분리해서 실행한다.

```sql
-- 목록 쿼리
select p1_0.id, p1_0.name, p1_0.price, p1_0.stock, p1_0.status
from product p1_0
where p1_0.deleted_at is null and p1_0.status<>?
order by p1_0.id desc
limit ?, ?

-- count 쿼리
select count(p1_0.id)
from product p1_0
where p1_0.deleted_at is null and p1_0.status<>?
```

`PageableExecutionUtils.getPage` 로 아래 경우에는 count 쿼리를 생략한다.
- 첫 페이지인데 조회된 건수가 `size` 보다 적을 때
- 마지막 페이지라서 조회된 건수가 `size` 보다 적을 때

## 2. 문제 (가설)

데이터가 많아지면(예: 20만 건, `size=20`) 다음이 문제가 될 수 있다.

### 2-1. 거의 모든 요청에서 count 가 실행된다
- 대부분의 페이지가 "꽉 찬 페이지"라서 count 생략 조건에 해당하지 않는다. 생략되는 건 사실상 마지막 페이지뿐이다.
- count 는 애플리케이션으로 **숫자 1개**만 돌려주지만, DB 는 조건에 맞는 행을 **세어야** 한다.
  MySQL(InnoDB)은 조건부 전체 행 수를 미리 저장하지 않는다.
- 현재 `product` 테이블에는 PK 외에 인덱스가 없다. → `deleted_at is null and status<>?` 조건 count 는 **풀 스캔**일 것으로 예상.

### 2-2. offset 이 커질수록 목록 쿼리도 느려진다
- `limit ?, ?` (offset 방식)는 깊은 페이지에서 앞의 행들을 읽고 버린다.
- 예: 1만 번째 페이지 = 앞 약 20만 건을 건너뛰고 20건을 반환.

### 2-3. 이번 작업에서 "추정"으로 말한 부분
- "20만 건 count 는 보통 수십 ms 수준" → **측정하지 않은 추정치**다. 환경에 따라 다르므로 아래 측정 계획으로 확인한다.

## 3. 측정 계획

### 3-1. 데이터 준비
- 로컬 개발 DB(`payment-mysql`)가 아니라 **별도 DB/스키마**에 20만 건(필요하면 100만 건)을 넣는다.
- 상태 분포를 현실적으로 섞는다. (ON_SALE 대부분, SOLD_OUT/STOPPED 일부, 삭제 일부)
- 시드 SQL 초안 (**미검증 — 실행 전에 확인할 것**):

```sql
SET SESSION cte_max_recursion_depth = 200000;

INSERT INTO product (name, price, stock, status, deleted_at, created_at, updated_at)
WITH RECURSIVE seq AS (
    SELECT 1 AS n UNION ALL SELECT n + 1 FROM seq WHERE n < 200000
)
SELECT CONCAT('상품', n),
       1000 + (n % 100) * 100,
       n % 50,
       CASE WHEN n % 50 = 0 THEN 'SOLD_OUT' WHEN n % 20 = 0 THEN 'STOPPED' ELSE 'ON_SALE' END,
       CASE WHEN n % 25 = 0 THEN NOW(6) ELSE NULL END,
       NOW(6), NOW(6)
FROM seq;
```

### 3-2. 측정 항목
| 항목 | 방법 |
|---|---|
| count 쿼리 실행 시간 | `EXPLAIN ANALYZE` |
| 목록 쿼리 (1페이지) | `EXPLAIN ANALYZE` |
| 목록 쿼리 (깊은 페이지, 예: offset 190000) | `EXPLAIN ANALYZE` |
| 실행 계획 | `type`, `key`, `rows` (풀 스캔 여부) |
| API 응답 시간 | 1페이지 / 중간 / 마지막 근처 페이지 |

## 4. 개선 후보

| 방법 | 내용 | 대가 |
|---|---|---|
| 인덱스 추가 | 조건·정렬 컬럼에 인덱스를 걸어 count/목록이 인덱스를 타게 함 (후보: `(deleted_at, status, id)` 등 — **EXPLAIN 으로 검증 필요**) | 쓰기 비용, 저장 공간 |
| `Slice` 로 전환 | 전체 건수 없이 "다음 페이지 있음/없음"만 반환 (`size+1` 건 조회) | 총 페이지 수를 보여줄 수 없음 |
| count 상한 | 최대 N 건까지만 세기 (예: "1000+") | 정확한 총 건수 포기 |
| count 캐싱 | 조건별 count 결과를 짧게 캐시 | 약간 틀릴 수 있음, 무효화 정책 필요 |
| 커서(keyset) 페이징 | `where id < :lastId order by id desc limit 20` | 임의 페이지 점프 불가 (offset 문제 해결) |
| 커버링 인덱스 + 지연 조인 | id 만 먼저 인덱스에서 뽑고, 그 id 로 본 테이블 조회 | 쿼리 복잡도 증가 |

## 5. 작업 체크리스트

- [ ] 별도 DB 에 20만 건 시드 데이터 생성
- [ ] 현재 쿼리 기준선 측정 (count / 1페이지 / 깊은 페이지) — 아래 "결과" 표에 기록
- [ ] 인덱스 후보 적용 후 재측정 (Flyway 마이그레이션으로 추가)
- [ ] `Slice` 또는 커서 방식 적용 여부 결정
- [ ] 적용한 방식의 테스트 추가 (기존 `ProductRepositoryTest` 의 count 생략/실행 테스트와의 관계 정리)
- [ ] 결론과 선택 이유 작성 (직접)

## 6. 결과 (측정 후 작성)

| 시나리오 | 개선 전 | 인덱스 추가 후 | 기타 방식 |
|---|---|---|---|
| count 쿼리 | | | |
| 목록 1페이지 | | | |
| 목록 깊은 페이지 | | | |
| 실행 계획(key/rows) | | | |

### 결론
(직접 작성)

## 7. 학습 포인트
- count 쿼리는 "데이터를 가져오는 비용"이 아니라 "DB 가 세는 비용"이다.
- 총 페이지 수가 정말 필요한 요구사항인가? (API 설계의 질문)
- offset 페이징은 페이지가 깊어질수록 느려진다.

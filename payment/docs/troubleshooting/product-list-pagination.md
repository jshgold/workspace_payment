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
- 시드 SQL 초안 (`V1__create_product.sql` / `Product.java` 와 컬럼명·CHECK 제약·상태 값은 대조 완료. **실제 실행은 아직 안 함**):
  - 삭제 조건을 `n % 23` 으로 둔 이유: `n % 25` 로 하면 SOLD_OUT(`n % 50 = 0`) 행이 전부 삭제 처리되어, `deleted_at is null` 조건에서 SOLD_OUT 이 하나도 남지 않는다.
  - 별도 스키마에 넣으려면 그 스키마에 `product` 테이블이 먼저 있어야 한다. (Flyway 적용 또는 V1 DDL 직접 실행)
  - 모든 행의 `created_at` 이 같다. `created_at` 정렬을 측정할 때는 값을 분산시킬 것.

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
       CASE WHEN n % 23 = 0 THEN NOW(6) ELSE NULL END,
       NOW(6), NOW(6)
FROM seq;
```

### 3-2. 측정 항목
| 항목 | 방법 |
|---|---|
| count 쿼리 실행 시간 | `EXPLAIN ANALYZE` |
| 목록 쿼리 (1페이지) | `EXPLAIN ANALYZE` |
| 목록 쿼리 (깊은 페이지, 예: offset 180000) | `EXPLAIN ANALYZE` (필터 후 건수가 약 183,653건이라 190000 은 끝을 넘어서 0행이 나온다) |
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

| 시나리오 | 개선 전 | 단일 인덱스 `(status)` | 조합 인덱스 `(status, deleted_at)` |
|---|---|---|---|
| count 쿼리 (`status <> 'STOPPED'`) | 약 34~46ms (2회 측정) | 약 49ms (`Table scan` 유지, 변화 없음) | 약 47ms (`Covering index range scan` 192,000행 → 필터 후 183,653. 인덱스는 타지만 시간 개선 없음) |
| count 쿼리 (`status = 'STOPPED'`) | 약 45.8ms (`Table scan`, 200,000행) | 약 26.5ms (`Index lookup` 8,000행 → 필터 후 7,652) | 약 3.84ms (`Covering index lookup` 7,652행, 본체 접근 없음) |
| 목록 1페이지 | 약 0.02ms | 약 0.04ms (PK 역방향 스캔 22행 유지, 변화 없음) | 약 0.04ms (PK 역방향 스캔 22행, 변화 없음) |
| 목록 깊은 페이지 (offset 180000) | 약 54ms | 약 47ms (PK 역방향 스캔 196,044행 유지, 계획 변화 없음) | 약 52ms (PK 역방향 스캔 196,044행, 변화 없음) |
| 실행 계획(key/rows) | count: `Table scan` 20만 행 / 1페이지: PK 역방향 스캔 22행 / 깊은 페이지: PK 역방향 스캔 196,044행 (180,000행을 읽고 버림) | | |

> 측정 환경: 로컬 Docker MySQL 8.0, `payment_perf` 스키마, 20만 건, `ANALYZE TABLE` 후, 캐시가 따뜻한 상태에서 단발 측정. 같은 count 쿼리가 45.7ms / 33.7ms 로 흔들렸으므로 절대값보다 실행 계획(풀 스캔 여부, 읽은 행 수)과 상대 비교를 본다.
> 필터 후 조건 일치 건수: 183,653건. 옵티마이저 예상 rows(약 1.7만)와 실제가 약 10배 달랐다. (status/deleted_at 에 인덱스·히스토그램 없음)
>
> "인덱스 추가 후" 열은 `idx_product_status (status)` 단일 인덱스만 추가한 결과다. (`payment_perf` 스키마에 직접 추가, Flyway 아님)
> `status = 'STOPPED'` 의 읽은 행 8,000 은 삭제된 STOPPED(348건)를 포함한 값이고, `deleted_at is null` 필터 후 7,652건이 남는다.
> 읽은 행은 200,000 → 8,000 으로 25배 줄었지만 시간은 약 1.7배만 줄었다. 인덱스에서 찾은 행마다 `deleted_at` 을 확인하려고 본체로 되돌아가기 때문으로 보였고, `(status, deleted_at)` 조합 인덱스(커버링)로 바꾸니 3.84ms 로 줄어 이 추정이 뒷받침됐다.

### 기타 방식 (SQL 직접 측정, 코드 미적용)

| 방식 | 쿼리 | 시간 | 읽은 행 |
|---|---|---|---|
| Slice 1페이지 | `LIMIT 0, 21` | 약 0.04ms | 23 |
| Slice 깊은 페이지 | `LIMIT 180000, 21` | 약 64ms | 196,046 |
| 커서 깊은 페이지 | `id < 3978 ... LIMIT 21` (offset 180000 과 같은 위치) | 약 0.16ms | 23 (`Index range scan` on PRIMARY) |

> 위 시간은 목록 쿼리만이다. Slice 가 줄이는 것은 별도로 실행되던 count(약 34~51ms)이고 이 표에는 들어 있지 않다.
> 단발 측정이라 흔들린다. (같은 offset 쿼리가 47~64ms 로 나왔다.) 읽은 행 수와 계획을 같이 본다.

### 결론
측정 조건: 로컬 Docker MySQL 8.0, 20만 건, 단발 측정. 절대값보다 읽은 행 수와 실행 계획의 차이를 근거로 삼는다.

**offset 방식**
- 총 건수와 임의 페이지 점프를 지원할 수 있다는 장점이 있다.
- 하지만 인덱스를 써도 offset 만큼의 행을 읽고 버리기 때문에 깊은 페이지에서 느려진다. (offset 180000: 약 54~64ms, 196,046행 읽음)
- 목록 화면 하나를 그리는 데 쿼리가 두 개 나간다. ① 현재 페이지의 데이터를 읽는 목록 쿼리, ② 총 건수와 총 페이지 수를 구하는 count 쿼리.
- count 쿼리는 첫 페이지·마지막 페이지가 아니면 거의 매 요청마다 실행되고, 조건에 맞는 행을 전부 세야 해서 풀 스캔이 된다. (약 34~51ms)

**인덱스로는 해결되지 않았다**
- `status`, `(status, deleted_at)` 인덱스는 맞는 행이 적은 조건(`status = 'STOPPED'`)의 count에는 효과가 컸다. (약 46ms → 3.84ms)
- 하지만 이 API가 실제로 쓰는 `status <> 'STOPPED'`(맞는 행 약 92%)의 count와 깊은 페이지는 그대로였다. 읽어야 하는 행의 개수가 줄지 않기 때문이다.

**Slice 방식**
- 쿼리를 하나로 줄인다. 총 건수를 구하는 count 쿼리를 없애고, 목록 쿼리만 `size + 1`건 조회한다. (20건이 필요하면 21건)
- 21번째 행이 있으면 "다음 페이지 있음", 없으면 "마지막 페이지"다. 화면이 알아야 하는 것이 총 건수가 아니라 "다음이 있는지"뿐이면(더보기 버튼, 무한 스크롤) 이것으로 충분해서 count 가 필요 없다.
- 그만큼 매 요청의 비용(약 34~51ms)이 줄지만, 총 건수와 총 페이지 수는 알 수 없다.
- offset 은 그대로라서 깊은 페이지는 여전히 느리다. (약 64ms, 196,046행 읽음)

**Cursor 방식**
- 인덱스로 시작 위치(`id < lastId`)에 바로 점프해서 필요한 행만 읽기 때문에 깊은 페이지에서도 빠르다. (약 0.16ms, 23행 읽음)
- Slice 처럼 `size + 1`건만 조회해서 다음 페이지 유무를 판단하므로 count 쿼리도 없다.
- 대신 정확한 위치(커서)를 알아야 하므로 임의 페이지 점프와 전체 건수 파악을 할 수 없다.
- 정렬 기준이 고유해야 한다. (가격순이면 `(price, id)` 복합 커서) 그리고 정렬 기준마다 맞는 인덱스가 필요하다.

## 7. 학습 포인트
- count 쿼리는 "데이터를 가져오는 비용"이 아니라 "DB 가 세는 비용"이다.
- 총 페이지 수가 정말 필요한 요구사항인가? (API 설계의 질문)
- offset 페이징은 페이지가 깊어질수록 느려진다.

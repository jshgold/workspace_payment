package com.payment.order.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;

import lombok.extern.slf4j.Slf4j;

/**
 * [측정 전용] phase-04 4-4: 대사·만료 대상 조회 쿼리의 실행 계획을 인덱스 조건별로 비교한다.
 * (docs/payment-reconciliation-discussion.md J)
 * <p>
 * 다른 통합 테스트와 컨테이너를 공유하지 않는다. 인덱스를 추가하고 대량 데이터를 넣으므로, 공유 컨테이너를 쓰면
 * 다른 테스트의 실행 계획과 속도에 영향을 준다. Spring 컨텍스트 없이 JDBC와 Flyway만 쓴다.
 * <p>
 * 인덱스를 지우지 않는다. (금지 목록: SQL DROP) 없음 → {@code (status)} 추가 → {@code (status, updated_at)} 추가 순서로
 * 측정하고, 각 단계는 {@code FORCE INDEX} / {@code IGNORE INDEX} 힌트로 비교 대상을 고정한다.
 * <p>
 * 기준 시각(N분 전)은 DB {@code NOW()}가 아니라 Java에서 계산해 넘긴다. (논의 G)
 * 결과는 {@code build/index-explain.md}에 저장한다.
 */
@Slf4j
@Tag("measurement")
class OrderReconciliationIndexExplainTest {

    private static final String IDX_STATUS = "idx_orders_status";
    private static final String IDX_STATUS_UPDATED_AT = "idx_orders_status_updated_at";

    /** 데이터의 기준 "지금". 대상 쿼리의 경과 시간은 모두 이 시각에서 계산한다. */
    private static final LocalDateTime NOW = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

    private static final int TERMINAL_COUNT = 196_959;
    private static final int PENDING_RECENT = 2_000;
    private static final int PENDING_OLD = 1_000;
    private static final int PAYING_RECENT = 15;
    private static final int PAYING_OLD = 5;
    private static final int PAYMENT_UNKNOWN_RECENT = 2;
    private static final int PAYMENT_UNKNOWN_OLD = 5;
    private static final int CANCELING_RECENT = 5;
    private static final int CANCELING_OLD = 2;
    private static final int CANCEL_UNKNOWN_RECENT = 1;
    private static final int CANCEL_UNKNOWN_OLD = 3;

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0")
            .withDatabaseName("payment_index_explain")
            .withUrlParam("rewriteBatchedStatements", "true")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci");

    /** 쿼리 이름 → (조회 상태, 경과 기준 분) */
    private static final Map<String, Target> TARGETS = new LinkedHashMap<>();

    static {
        TARGETS.put("만료 (PENDING 30분)", new Target("PENDING", 30, PENDING_OLD));
        TARGETS.put("승인 대사 (PAYMENT_UNKNOWN 1분)", new Target("PAYMENT_UNKNOWN", 1, PAYMENT_UNKNOWN_OLD));
        TARGETS.put("승인 대사 (PAYING 5분)", new Target("PAYING", 5, PAYING_OLD));
        TARGETS.put("취소 대사 (CANCEL_UNKNOWN 1분)", new Target("CANCEL_UNKNOWN", 1, CANCEL_UNKNOWN_OLD));
        TARGETS.put("취소 대사 (CANCELING 5분)", new Target("CANCELING", 5, CANCELING_OLD));
    }

    private static final int LIMIT = 500;

    private record Target(String status, int minutes, int targetCount) {
        /** 한 회차에 고르는 건수. 대상이 LIMIT보다 많으면 LIMIT까지만 고른다. */
        int expectedCount() {
            return Math.min(targetCount, LIMIT);
        }
    }

    @BeforeAll
    static void setUp() throws SQLException {
        MYSQL.start();
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("10") // V11이 측정 대상 인덱스를 만든다. "인덱스 없음"부터 비교하려고 그 전까지만 올린다
                .load()
                .migrate();
        seed();
    }

    @AfterAll
    static void tearDown() {
        MYSQL.stop();
    }

    @Test
    @DisplayName("주문 20만 건(대상 PENDING 1000·PAYING 5·결과 모름 8·CANCELING 2)에서 인덱스 없음 / (status) / (status, updated_at)별로 대상 조회 5개의 실행 계획을 비교하고, 세 조건 모두 같은 대상을 고른다")
    void compareExplainByIndex() throws SQLException, IOException {
        StringBuilder report = new StringBuilder();
        report.append("# 대사·만료 대상 조회 실행 계획 비교\n\n");
        report.append("- 측정 시각 기준(NOW): ").append(NOW).append('\n');
        report.append("- 데이터: ").append(summary()).append("\n\n");

        try (Connection conn = connect()) {
            // given: 인덱스 없음 (기존 인덱스: PK, uk_orders_order_no, idx_orders_product_id, idx_orders_member_id)
            Map<String, Integer> baseline = measure(conn, report, "1. 인덱스 없음", "");

            // given: (status) 추가
            createIndex(conn, IDX_STATUS, "status");
            Map<String, Integer> statusOnly = measure(conn, report, "2. (status)", "FORCE INDEX (" + IDX_STATUS + ")");

            // given: (status, updated_at) 추가
            createIndex(conn, IDX_STATUS_UPDATED_AT, "status, updated_at");
            Map<String, Integer> composite = measure(conn, report, "3. (status, updated_at)",
                    "FORCE INDEX (" + IDX_STATUS_UPDATED_AT + ")");

            // 두 인덱스가 모두 있을 때 옵티마이저가 스스로 고르는 것
            Map<String, Integer> optimizerChoice = measure(conn, report, "4. 둘 다 있을 때 옵티마이저 선택 (힌트 없음)", "");

            // then: 인덱스는 읽는 방법만 바꾸고, 고르는 대상은 바꾸지 않는다
            TARGETS.forEach((name, target) -> {
                assertThat(baseline.get(name)).as(name).isEqualTo(target.expectedCount());
                assertThat(statusOnly.get(name)).as(name).isEqualTo(target.expectedCount());
                assertThat(composite.get(name)).as(name).isEqualTo(target.expectedCount());
                assertThat(optimizerChoice.get(name)).as(name).isEqualTo(target.expectedCount());
            });

            // ---------------------------------------------------------------- 시나리오 B: 평소 상태
            // given: 만료가 1분마다 잘 돌고 있다. 30분 지난 PENDING은 30건뿐이고, 최근 30분 안의 PENDING은 3000건이다.
            toSteadyState(conn);
            report.append("---\n\n# 시나리오 B: 평소 상태 (만료 쿼리만)\n\n");
            report.append("- 데이터: 시나리오 A에서 30분 지난 PENDING 1000건 중 ").append(PENDING_OLD - STEADY_PENDING_OLD)
                    .append("건을 EXPIRED로 바꾸고(만료가 처리한 것처럼), 최근 PENDING ").append(STEADY_PENDING_RECENT_ADDED)
                    .append("건을 더 넣음 → PENDING ").append(STEADY_PENDING_OLD + PENDING_RECENT + STEADY_PENDING_RECENT_ADDED)
                    .append("건 (30분 지남 ").append(STEADY_PENDING_OLD).append(")\n\n");

            Map<String, Target> steady = Map.of("만료 (PENDING 30분)", new Target("PENDING", 30, STEADY_PENDING_OLD));
            String ignoreBoth = "IGNORE INDEX (" + IDX_STATUS + ", " + IDX_STATUS_UPDATED_AT + ")";
            Map<String, Integer> steadyNone = measure(conn, report, "B-1. 인덱스 없음", ignoreBoth, steady);
            Map<String, Integer> steadyStatus = measure(conn, report, "B-2. (status)", "FORCE INDEX (" + IDX_STATUS + ")", steady);
            Map<String, Integer> steadyComposite = measure(conn, report, "B-3. (status, updated_at)",
                    "FORCE INDEX (" + IDX_STATUS_UPDATED_AT + ")", steady);
            Map<String, Integer> steadyOptimizer = measure(conn, report, "B-4. 옵티마이저 선택 (힌트 없음)", "", steady);

            Files.writeString(Path.of("build", "index-explain.md"), report.toString());
            log.info("\n{}", report);

            // then: 시나리오 B에서도 세 조건 모두 같은 30건을 고른다
            for (Map<String, Integer> result : List.of(steadyNone, steadyStatus, steadyComposite, steadyOptimizer)) {
                assertThat(result.get("만료 (PENDING 30분)")).isEqualTo(STEADY_PENDING_OLD);
            }
        }
    }

    private static final int STEADY_PENDING_OLD = 30;
    private static final int STEADY_PENDING_RECENT_ADDED = 1_000;

    /** 시나리오 A의 데이터를 평소 상태로 바꾼다. 오래된 PENDING을 만료시키고 최근 PENDING을 더 넣는다. */
    private void toSteadyState(Connection conn) throws SQLException {
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE orders SET status = 'EXPIRED', updated_at = ?
                 WHERE status = 'PENDING' AND updated_at < ?
                 ORDER BY id LIMIT ?""")) {
            ps.setTimestamp(1, Timestamp.valueOf(NOW.minusSeconds(30)));
            ps.setTimestamp(2, Timestamp.valueOf(NOW.minusMinutes(30)));
            ps.setInt(3, PENDING_OLD - STEADY_PENDING_OLD);
            assertThat(ps.executeUpdate()).isEqualTo(PENDING_OLD - STEADY_PENDING_OLD);
        }
        long productId;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT MIN(id) FROM product")) {
            rs.next();
            productId = rs.getLong(1);
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO orders (order_no, member_id, product_id, quantity, total_price, status, created_at, updated_at)
                VALUES (?, ?, ?, 1, 10000, ?, ?, ?)""")) {
            Inserter in = new Inserter(ps, productId, new Random(7));
            in.addSpread("PENDING", STEADY_PENDING_RECENT_ADDED, NOW.minusMinutes(29), NOW.minusSeconds(1), true);
            in.flush();
        }
        conn.commit();
        conn.setAutoCommit(true);
        try (Statement st = conn.createStatement()) {
            st.execute("ANALYZE TABLE orders");
        }
    }

    // ---------------------------------------------------------------- 측정

    private Map<String, Integer> measure(Connection conn, StringBuilder report, String title, String hint) throws SQLException {
        return measure(conn, report, title, hint, TARGETS);
    }

    private Map<String, Integer> measure(Connection conn, StringBuilder report, String title, String hint,
                                         Map<String, Target> targets) throws SQLException {
        report.append("## ").append(title).append("\n\n");
        report.append("| 쿼리 | type | key | rows (추정) | filtered | Extra | 실제 대상 건수 |\n");
        report.append("|---|---|---|---|---|---|---|\n");

        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> analyzes = new ArrayList<>();
        for (Map.Entry<String, Target> entry : targets.entrySet()) {
            String sql = targetQuery(hint);
            Timestamp cutoff = Timestamp.valueOf(NOW.minusMinutes(entry.getValue().minutes()));
            String status = entry.getValue().status();

            int count = countTargets(conn, sql, status, cutoff);
            counts.put(entry.getKey(), count);

            try (PreparedStatement ps = conn.prepareStatement("EXPLAIN " + sql)) {
                ps.setString(1, status);
                ps.setTimestamp(2, cutoff);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    report.append("| ").append(entry.getKey())
                            .append(" | ").append(rs.getString("type"))
                            .append(" | ").append(rs.getString("key"))
                            .append(" | ").append(rs.getString("rows"))
                            .append(" | ").append(rs.getString("filtered"))
                            .append(" | ").append(rs.getString("Extra"))
                            .append(" | ").append(count)
                            .append(" |\n");
                }
            }

            // 한 번 실행해 버퍼 풀을 데운 뒤(countTargets) EXPLAIN ANALYZE로 실제 읽은 행 수와 시간을 본다.
            try (PreparedStatement ps = conn.prepareStatement("EXPLAIN ANALYZE " + sql)) {
                ps.setString(1, status);
                ps.setTimestamp(2, cutoff);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    analyzes.add("**" + entry.getKey() + "**\n```\n" + rs.getString(1).strip() + "\n```\n");
                }
            }
        }

        report.append("\n<details><summary>EXPLAIN ANALYZE</summary>\n\n");
        analyzes.forEach(a -> report.append(a).append('\n'));
        report.append("</details>\n\n");
        return counts;
    }

    /** 대사·만료가 대상을 고르는 쿼리. 락 없이 id만 읽는다. (논의 I: 고른 뒤 주문마다 FOR UPDATE) */
    private static String targetQuery(String hint) {
        return "SELECT id FROM orders " + hint + " WHERE status = ? AND updated_at < ? LIMIT " + LIMIT;
    }

    private int countTargets(Connection conn, String sql, String status, Timestamp cutoff) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setTimestamp(2, cutoff);
            int count = 0;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    count++;
                }
            }
            return count;
        }
    }

    private void createIndex(Connection conn, String name, String columns) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE INDEX " + name + " ON orders (" + columns + ")");
            st.execute("ANALYZE TABLE orders");
        }
    }

    // ---------------------------------------------------------------- 데이터

    private static String summary() {
        return "주문 %,d건 = 끝난 주문(PAID 70%%, FAILED·EXPIRED·CANCELED 각 10%%, 최근 90일) %,d + PENDING %d(30분 지남 %d) + PAYING %d(5분 지남 %d) + PAYMENT_UNKNOWN %d(1분 지남 %d) + CANCELING %d(5분 지남 %d) + CANCEL_UNKNOWN %d(1분 지남 %d)"
                .formatted(total(), TERMINAL_COUNT,
                        PENDING_RECENT + PENDING_OLD, PENDING_OLD,
                        PAYING_RECENT + PAYING_OLD, PAYING_OLD,
                        PAYMENT_UNKNOWN_RECENT + PAYMENT_UNKNOWN_OLD, PAYMENT_UNKNOWN_OLD,
                        CANCELING_RECENT + CANCELING_OLD, CANCELING_OLD,
                        CANCEL_UNKNOWN_RECENT + CANCEL_UNKNOWN_OLD, CANCEL_UNKNOWN_OLD);
    }

    private static int total() {
        return TERMINAL_COUNT + PENDING_RECENT + PENDING_OLD + PAYING_RECENT + PAYING_OLD
                + PAYMENT_UNKNOWN_RECENT + PAYMENT_UNKNOWN_OLD + CANCELING_RECENT + CANCELING_OLD
                + CANCEL_UNKNOWN_RECENT + CANCEL_UNKNOWN_OLD;
    }

    /**
     * 실제 운영과 비슷한 분포를 id 순(= 생성 순)으로 넣는다. 오래된 끝난 주문이 먼저, 진행 중인 주문이 나중이다.
     * "N분 지남" 건은 기준보다 확실히 오래되게, "최근" 건은 확실히 덜 지나게 시각을 잡는다.
     */
    private static void seed() throws SQLException {
        Random random = new Random(42);
        try (Connection conn = connect()) {
            conn.setAutoCommit(false);
            long productId;
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("""
                        INSERT INTO product (name, price, stock, status, created_at, updated_at)
                        VALUES ('측정용 상품', 10000, 1000000, 'ON_SALE', NOW(6), NOW(6))""", Statement.RETURN_GENERATED_KEYS);
                try (ResultSet keys = st.getGeneratedKeys()) {
                    keys.next();
                    productId = keys.getLong(1);
                }
            }

            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO orders (order_no, member_id, product_id, quantity, total_price, status, created_at, updated_at)
                    VALUES (?, ?, ?, 1, 10000, ?, ?, ?)""")) {
                Inserter in = new Inserter(ps, productId, random);

                // 끝난 주문: 최근 90일 ~ 1일 전, 오래된 것부터
                long span = ChronoUnit.SECONDS.between(NOW.minusDays(90), NOW.minusDays(1));
                for (int i = 0; i < TERMINAL_COUNT; i++) {
                    LocalDateTime created = NOW.minusDays(90).plusSeconds(span * i / TERMINAL_COUNT);
                    int r = random.nextInt(100);
                    String status = r < 70 ? "PAID" : r < 80 ? "FAILED" : r < 90 ? "EXPIRED" : "CANCELED";
                    in.add(status, created, created.plusMinutes(1));
                }

                // 진행 중·결과 모름: 오래된 것(기준을 넘김) 먼저, 최근 것 나중
                in.addSpread("PENDING", PENDING_OLD, NOW.minusHours(3), NOW.minusMinutes(31), true);
                in.addSpread("PAYING", PAYING_OLD, NOW.minusMinutes(60), NOW.minusMinutes(6), false);
                in.addSpread("PAYMENT_UNKNOWN", PAYMENT_UNKNOWN_OLD, NOW.minusMinutes(30), NOW.minusMinutes(2), false);
                in.addSpread("CANCELING", CANCELING_OLD, NOW.minusMinutes(30), NOW.minusMinutes(6), false);
                in.addSpread("CANCEL_UNKNOWN", CANCEL_UNKNOWN_OLD, NOW.minusMinutes(30), NOW.minusMinutes(2), false);

                in.addSpread("PENDING", PENDING_RECENT, NOW.minusMinutes(29), NOW.minusSeconds(1), true);
                in.addSpread("PAYING", PAYING_RECENT, NOW.minusMinutes(4), NOW.minusSeconds(1), false);
                in.addSpread("PAYMENT_UNKNOWN", PAYMENT_UNKNOWN_RECENT, NOW.minusSeconds(50), NOW.minusSeconds(10), false);
                in.addSpread("CANCELING", CANCELING_RECENT, NOW.minusMinutes(4), NOW.minusSeconds(1), false);
                in.addSpread("CANCEL_UNKNOWN", CANCEL_UNKNOWN_RECENT, NOW.minusSeconds(50), NOW.minusSeconds(10), false);
                in.flush();
            }
            conn.commit();

            try (Statement st = conn.createStatement()) {
                st.execute("ANALYZE TABLE orders");
            }
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static final class Inserter {
        private static final int BATCH = 5_000;
        private final PreparedStatement ps;
        private final long productId;
        private final Random random;
        private int pending;

        private Inserter(PreparedStatement ps, long productId, Random random) {
            this.ps = ps;
            this.productId = productId;
            this.random = random;
        }

        /**
         * from~to 사이에 고르게 count건을 넣는다.
         * PENDING은 만든 뒤 바뀌지 않으므로 created_at = updated_at, 나머지는 updated_at이 상태가 바뀐 시각이라 생성이 조금 앞선다.
         */
        void addSpread(String status, int count, LocalDateTime from, LocalDateTime to, boolean createdEqualsUpdated)
                throws SQLException {
            long span = ChronoUnit.SECONDS.between(from, to);
            for (int i = 0; i < count; i++) {
                LocalDateTime updated = from.plusSeconds(count == 1 ? 0 : span * i / (count - 1));
                LocalDateTime created = createdEqualsUpdated ? updated : updated.minusMinutes(2);
                add(status, created, updated);
            }
        }

        void add(String status, LocalDateTime created, LocalDateTime updated) throws SQLException {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setLong(2, 1 + random.nextInt(10_000));
            ps.setLong(3, productId);
            ps.setString(4, status);
            ps.setTimestamp(5, Timestamp.valueOf(created));
            ps.setTimestamp(6, Timestamp.valueOf(updated));
            ps.addBatch();
            if (++pending >= BATCH) {
                flush();
            }
        }

        void flush() throws SQLException {
            ps.executeBatch();
            pending = 0;
        }
    }
}

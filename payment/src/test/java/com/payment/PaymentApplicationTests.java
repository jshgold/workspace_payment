package com.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.support.IntegrationTestSupport;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PaymentApplicationTests extends IntegrationTestSupport {

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("컨텍스트가 로딩되고, 개발 DB가 아닌 Testcontainers MySQL 8.0에 연결된다")
    void contextLoads() throws Exception {
        // given
        String url;
        try (var connection = dataSource.getConnection()) {
            url = connection.getMetaData().getURL();
        }

        // when
        String version = jdbcTemplate.queryForObject("SELECT @@version", String.class);
        String isolation = jdbcTemplate.queryForObject("SELECT @@transaction_isolation", String.class);

        // then
        assertThat(version).startsWith("8.0");
        assertThat(isolation).isEqualTo("REPEATABLE-READ");
        assertThat(url).contains("payment_test").doesNotContain(":3306/payment?");
    }

    @Test
    @DisplayName("Flyway가 테스트 컨테이너 DB에 스키마 이력 테이블을 만든다")
    void flywayHistoryTableExists() {
        // given // when
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name = 'flyway_schema_history'",
                Integer.class);

        // then
        assertThat(count).isEqualTo(1);
    }
}

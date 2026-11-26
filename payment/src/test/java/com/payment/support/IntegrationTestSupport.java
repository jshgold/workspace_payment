package com.payment.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 실제 MySQL 컨테이너 위에서 도는 통합·동시성 테스트의 공통 부모.
 * <p>
 * 컨테이너는 JVM 당 한 번만 띄워서 모든 테스트 클래스가 공유한다. (종료는 Testcontainers의 Ryuk가 담당)
 * {@link ServiceConnection}이 컨테이너의 접속 정보를 DataSource에 자동으로 연결하므로
 * application-local.yaml의 datasource 설정은 테스트에서 사용되지 않는다.
 */
@SpringBootTest(properties = "app.scheduling.enabled=false") // 대사·만료가 백그라운드에서 테스트 데이터를 바꾸지 않게
@ActiveProfiles("local")
public abstract class IntegrationTestSupport {

    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0")
            .withDatabaseName("payment_test")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci");

    static {
        MYSQL.start();
    }
}

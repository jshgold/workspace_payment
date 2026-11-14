package com.payment.product.infrastructure;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MySQL Named Lock({@code GET_LOCK} / {@code RELEASE_LOCK}) 실행기.
 * <p>
 * Named Lock은 락을 잡은 "커넥션(세션)"에 묶인다. 그래서 같은 커넥션에서 GET_LOCK → 작업 → RELEASE_LOCK을 해야 하고,
 * 작업 안의 {@code @Transactional}은 풀에서 다른 커넥션을 따로 빌린다. 한 요청이 커넥션을 2개 쓰는 구조다.
 * <p>
 * 락 대기자가 비즈니스 풀을 채워 락을 쥔 요청이 트랜잭션용 커넥션을 못 얻는 풀 데드락을 막기 위해,
 * 기본값은 <b>락 전용 풀</b>(이 클래스 내부 필드, 빈으로 노출하지 않음)을 따로 만들어 쓴다.
 * 이 풀은 첫 사용 시점에 lazy하게 만들어지고(minimumIdle=0), 사용하지 않으면 커넥션을 열지 않는다.
 * <ul>
 *   <li>{@code app.named-lock.pool-size}: 락 전용 풀 크기 (기본 110, 동시 락 대기자 수 이상으로 잡는다)</li>
 *   <li>{@code app.named-lock.shared-pool}: true면 락 전용 풀 대신 비즈니스 풀을 같이 쓴다. (풀 데드락 재현 실험용)</li>
 * </ul>
 * 이 클래스를 지우면 락 전용 풀 설정도 같이 사라진다. 다른 설정은 건드리지 않는다.
 */
@Slf4j
@Component
public class NamedLockExecutor {

    private static final String GET_LOCK_SQL = "SELECT GET_LOCK(?, ?)";
    private static final String RELEASE_LOCK_SQL = "SELECT RELEASE_LOCK(?)";

    private final DataSource lockDataSource;
    private final HikariDataSource ownedPool;

    public NamedLockExecutor(
            DataSource businessDataSource,
            @Value("${app.named-lock.shared-pool:false}") boolean sharedPool,
            @Value("${app.named-lock.pool-size:110}") int poolSize) {
        if (sharedPool) {
            this.lockDataSource = businessDataSource;
            this.ownedPool = null;
        } else {
            this.ownedPool = createLockPool(businessDataSource, poolSize);
            this.lockDataSource = this.ownedPool;
        }
    }

    /**
     * 락 획득 → 작업 실행 → 락 해제 순서를 보장한다.
     * 작업이 끝난(트랜잭션이 커밋된) 뒤에 락을 풀기 때문에, 다음 대기자는 커밋된 최신 상태를 읽는다.
     */
    public void executeWithLock(String lockName, int timeoutSeconds, Runnable action) {
        Connection connection = openConnection();
        boolean lockHeld = false;
        boolean discardConnection = false;
        try {
            lockHeld = tryLock(connection, lockName, timeoutSeconds);
            if (!lockHeld) {
                log.warn("Named Lock 획득 실패(타임아웃). name={}, timeout={}s", lockName, timeoutSeconds);
                throw new BusinessException(ErrorCode.LOCK_ACQUISITION_FAILED);
            }
            action.run();
        } catch (SQLException e) {
            discardConnection = true; // 락 상태를 알 수 없으므로 풀로 돌려보내지 않는다.
            log.error("Named Lock 처리 중 SQL 오류. name={}", lockName, e);
            throw new BusinessException(ErrorCode.LOCK_ACQUISITION_FAILED);
        } finally {
            if (lockHeld && !release(connection, lockName)) {
                discardConnection = true;
            }
            closeConnection(connection, discardConnection);
        }
    }

    @PreDestroy
    void shutdown() {
        if (ownedPool != null) {
            ownedPool.close();
        }
    }

    private Connection openConnection() {
        try {
            return lockDataSource.getConnection();
        } catch (SQLException e) {
            log.error("Named Lock용 커넥션을 얻지 못했습니다.", e);
            throw new BusinessException(ErrorCode.LOCK_ACQUISITION_FAILED);
        }
    }

    private boolean tryLock(Connection connection, String lockName, int timeoutSeconds) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(GET_LOCK_SQL)) {
            statement.setString(1, lockName);
            statement.setInt(2, timeoutSeconds);
            try (ResultSet rs = statement.executeQuery()) {
                // 1: 획득, 0: 타임아웃, NULL: 오류
                return rs.next() && rs.getInt(1) == 1 && !rs.wasNull();
            }
        }
    }

    /** @return 정상 해제 여부. false면 이 커넥션에 락이 남아 있을 수 있다. */
    private boolean release(Connection connection, String lockName) {
        try (PreparedStatement statement = connection.prepareStatement(RELEASE_LOCK_SQL)) {
            statement.setString(1, lockName);
            try (ResultSet rs = statement.executeQuery()) {
                boolean released = rs.next() && rs.getInt(1) == 1 && !rs.wasNull();
                if (!released) {
                    log.warn("Named Lock 해제 결과가 1이 아닙니다. name={}", lockName);
                }
                return released;
            }
        } catch (SQLException e) {
            log.error("Named Lock 해제 중 SQL 오류. name={}", lockName, e);
            return false;
        }
    }

    private void closeConnection(Connection connection, boolean discard) {
        try {
            if (discard && lockDataSource instanceof HikariDataSource hikari) {
                hikari.evictConnection(connection); // 락이 남은 세션이 풀에 재사용되지 않도록 물리 연결을 버린다.
            }
            connection.close();
        } catch (SQLException e) {
            log.warn("Named Lock용 커넥션을 닫는 중 오류가 발생했습니다.", e);
        }
    }

    private static HikariDataSource createLockPool(DataSource businessDataSource, int poolSize) {
        try {
            HikariDataSource origin = businessDataSource.unwrap(HikariDataSource.class);
            HikariDataSource pool = new HikariDataSource(); // 인자 없는 생성자: 첫 getConnection 때 풀 초기화
            pool.setPoolName("named-lock-pool");
            pool.setJdbcUrl(origin.getJdbcUrl());
            pool.setUsername(origin.getUsername());
            pool.setPassword(origin.getPassword());
            pool.setMaximumPoolSize(poolSize);
            pool.setMinimumIdle(0);
            pool.setIdleTimeout(10_000);
            return pool;
        } catch (SQLException e) {
            throw new IllegalStateException("Named Lock 전용 풀을 만들 수 없습니다.", e);
        }
    }
}

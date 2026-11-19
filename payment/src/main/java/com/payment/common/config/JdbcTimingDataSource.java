package com.payment.common.config;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import com.payment.common.util.JdbcTiming;

import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * [측정 전용 계측] 커넥션을 빌리는 시간({@code getConnection})과 {@code COMMIT} 시간을 재서 {@link JdbcTiming}에 쌓는다.
 * <p>
 * {@link JdbcTiming} 기록 중인 스레드에서만 재고, 그 밖에는 원래 DataSource를 그대로 호출한다.
 * Spring + JPA는 트랜잭션을 시작할 때 커넥션을 빌리므로, 여기서 잰 시간이 곧 트랜잭션별 커넥션 풀 대기 시간이다.
 */
public class JdbcTimingDataSource extends DelegatingDataSource {

    public JdbcTimingDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (!JdbcTiming.isActive()) {
            return super.getConnection();
        }
        long startedAt = System.nanoTime();
        Connection connection = super.getConnection();
        JdbcTiming.addConnectionWait(System.nanoTime() - startedAt);
        return commitTimingProxy(connection);
    }

    private static Connection commitTimingProxy(Connection target) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> isCommit(method) ? timedCommit(target, method) : invoke(target, method, args));
    }

    private static boolean isCommit(Method method) {
        return "commit".equals(method.getName()) && method.getParameterCount() == 0;
    }

    private static Object timedCommit(Connection target, Method method) throws Throwable {
        long startedAt = System.nanoTime();
        try {
            return invoke(target, method, null);
        } finally {
            JdbcTiming.addCommit(System.nanoTime() - startedAt);
        }
    }

    private static Object invoke(Connection target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}

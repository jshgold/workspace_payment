package com.payment.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import com.payment.common.util.JdbcTiming;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** [측정 전용 계측] 커넥션 대기·COMMIT 시간이 기록 중인 스레드에서만 쌓이고, 원래 동작은 그대로인지 확인한다. */
@ExtendWith(MockitoExtension.class)
class JdbcTimingDataSourceTest {

    @Mock
    DataSource target;

    @Mock
    Connection connection;

    @AfterEach
    void tearDown() {
        JdbcTiming.end();
    }

    @Test
    @DisplayName("기록 중이면 커넥션 획득 횟수가 쌓이고, 받은 커넥션의 commit은 원래 커넥션으로 전달된다")
    void recordsConnectionAndCommitWhenActive() throws SQLException {
        // given
        given(target.getConnection()).willReturn(connection);
        JdbcTimingDataSource dataSource = new JdbcTimingDataSource(target);
        JdbcTiming.begin();

        // when
        Connection wrapped = dataSource.getConnection();
        wrapped.commit();
        JdbcTiming.Snapshot snapshot = JdbcTiming.end();

        // then
        verify(connection).commit();
        assertThat(snapshot.connections()).isEqualTo(1);
        assertThat(snapshot.connectionWaitNanos()).isPositive();
        assertThat(snapshot.commitNanos()).isPositive();
    }

    @Test
    @DisplayName("기록 중이 아니면 원래 커넥션을 감싸지 않고 그대로 돌려준다")
    void passesThroughWhenInactive() throws SQLException {
        // given
        given(target.getConnection()).willReturn(connection);
        JdbcTimingDataSource dataSource = new JdbcTimingDataSource(target);

        // when
        Connection result = dataSource.getConnection();

        // then
        assertThat(result).isSameAs(connection);
    }

    @Test
    @DisplayName("commit이 실패하면 원래 SQLException이 그대로 던져진다")
    void commitFailurePropagatesOriginalException() throws SQLException {
        // given
        SQLException failure = new SQLException("commit failed");
        given(target.getConnection()).willReturn(connection);
        willThrow(failure).given(connection).commit();
        JdbcTimingDataSource dataSource = new JdbcTimingDataSource(target);
        JdbcTiming.begin();
        Connection wrapped = dataSource.getConnection();

        // when // then
        assertThatThrownBy(wrapped::commit).isSameAs(failure);
    }
}

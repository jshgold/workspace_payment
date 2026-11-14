package com.payment.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;

/**
 * 테스트용 Redis 관찰 도구. 락 구현과 별개의 연결로 Redis 서버가 실제로 받은 명령 수를 읽고, 키를 직접 확인·삭제한다.
 * <p>
 * 명령 수는 {@code INFO stats}의 {@code total_commands_processed} 차이로 구한다.
 * 이 도구 자신의 INFO 호출(2회)과 락 클라이언트의 최초 연결 핸드셰이크 명령은 섞이지만, 폴링 수백~수천 건 비교에는 영향이 작다.
 */
public class RedisCommandStats implements AutoCloseable {

    private static final Pattern TOTAL_COMMANDS = Pattern.compile("total_commands_processed:(\\d+)");

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private long markedCommands;

    public RedisCommandStats(String address) {
        this.client = RedisClient.create(RedisURI.create(address));
        this.connection = client.connect();
    }

    /** 지금까지의 명령 수를 기준점으로 기록한다. */
    public void mark() {
        markedCommands = totalCommandsProcessed();
    }

    /** 기준점 이후 Redis가 처리한 명령 수 */
    public long commandsSinceMark() {
        return totalCommandsProcessed() - markedCommands;
    }

    public boolean exists(String key) {
        return connection.sync().exists(key) == 1L;
    }

    /** 소유자 확인 없이 키를 지운다. (토큰 없는 해제 실험용) */
    public void delete(String key) {
        connection.sync().del(key);
    }

    private long totalCommandsProcessed() {
        Matcher matcher = TOTAL_COMMANDS.matcher(connection.sync().info("stats"));
        if (!matcher.find()) {
            throw new IllegalStateException("INFO stats에서 total_commands_processed를 찾을 수 없습니다.");
        }
        return Long.parseLong(matcher.group(1));
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }
}

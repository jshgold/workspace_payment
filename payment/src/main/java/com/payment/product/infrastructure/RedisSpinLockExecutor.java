package com.payment.product.infrastructure;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Redis 스핀락 실행기. Redisson 없이 Redis 명령 두 개만으로 직접 구현한다.
 * <ul>
 *   <li>획득: {@code SET key token NX PX leaseTime}을 성공할 때까지 짧게 쉬며 반복한다. (폴링)</li>
 *   <li>해제: Lua 스크립트로 "값이 내 토큰일 때만 DEL"을 원자적으로 실행한다.
 *       토큰 확인 없이 지우면 leaseTime이 지나 남이 얻은 락을 내가 지워 버린다.</li>
 * </ul>
 * Redisson과 달리 Pub/Sub 알림, 재진입, 자동 연장(watchdog), 공정성이 없다.
 * 락이 풀려도 다음 폴링 때까지 대기자는 모르고, 대기자가 많을수록 Redis 요청이 늘어난다.
 * <p>
 * 클라이언트는 첫 사용 시점에 연결한다. 이 클래스를 지우면 스핀락 관련 설정도 같이 사라진다.
 * <ul>
 *   <li>{@code app.redis.spin.min-interval-ms} / {@code app.redis.spin.max-interval-ms}: 재시도 간격 범위 (기본 10~30ms)</li>
 * </ul>
 */
@Slf4j
@Component
public class RedisSpinLockExecutor {

    private static final String RELEASE_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

    private final String address;
    private final long minIntervalMs;
    private final long maxIntervalMs;

    private volatile RedisClient client;
    private volatile StatefulRedisConnection<String, String> connection;

    /** 실험 기록용: SET NX 시도 누적 횟수 (폴링 횟수) */
    private final AtomicLong attemptCount = new AtomicLong();
    /** 실험 기록용: 해제하려 했지만 이미 내 락이 아니었던 횟수 (leaseTime 만료) */
    private final AtomicLong leaseExpiredCount = new AtomicLong();

    public RedisSpinLockExecutor(
            @Value("${app.redis.address:redis://localhost:6379}") String address,
            @Value("${app.redis.spin.min-interval-ms:10}") long minIntervalMs,
            @Value("${app.redis.spin.max-interval-ms:30}") long maxIntervalMs) {
        this.address = address;
        this.minIntervalMs = minIntervalMs;
        this.maxIntervalMs = maxIntervalMs;
    }

    /**
     * 락 획득 → 작업 실행 → 락 해제 순서를 보장한다.
     * 작업이 끝난(트랜잭션이 커밋된) 뒤에 락을 풀기 때문에, 다음 대기자는 커밋된 최신 상태를 읽는다.
     */
    public void executeWithLock(String lockName, long waitTimeMs, long leaseTimeMs, Runnable action) {
        String token = tryAcquire(lockName, waitTimeMs, leaseTimeMs);
        if (token == null) {
            log.warn("Redis 스핀락 획득 실패(waitTime 초과). name={}, waitTime={}ms", lockName, waitTimeMs);
            throw new BusinessException(ErrorCode.LOCK_ACQUISITION_FAILED);
        }
        try {
            action.run();
        } finally {
            if (!release(lockName, token)) {
                leaseExpiredCount.incrementAndGet();
                log.warn("Redis 스핀락이 해제 전에 leaseTime으로 만료되었습니다. name={}", lockName);
            }
        }
    }

    /**
     * waitTime 동안 반복해서 락 획득을 시도한다.
     *
     * @return 획득에 성공하면 소유자 토큰, waitTime 안에 못 얻으면 null
     */
    public String tryAcquire(String lockName, long waitTimeMs, long leaseTimeMs) {
        String token = UUID.randomUUID().toString();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitTimeMs);
        RedisCommands<String, String> commands = commands();
        while (true) {
            attemptCount.incrementAndGet();
            String result = commands.set(lockName, token, SetArgs.Builder.nx().px(leaseTimeMs));
            if ("OK".equals(result)) {
                return token;
            }
            if (System.nanoTime() >= deadline) {
                return null;
            }
            if (!sleepInterval()) {
                return null;
            }
        }
    }

    /** @return 내 토큰의 락을 지웠으면 true, 이미 만료되었거나 남의 락이면 false (남의 락은 건드리지 않는다) */
    public boolean release(String lockName, String token) {
        Long deleted = commands().eval(RELEASE_SCRIPT, ScriptOutputType.INTEGER, new String[]{lockName}, token);
        return deleted != null && deleted == 1L;
    }

    public long attemptCount() {
        return attemptCount.get();
    }

    public long leaseExpiredCount() {
        return leaseExpiredCount.get();
    }

    public void resetCounters() {
        attemptCount.set(0);
        leaseExpiredCount.set(0);
    }

    private boolean sleepInterval() {
        long millis = ThreadLocalRandom.current().nextLong(minIntervalMs, maxIntervalMs + 1);
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private RedisCommands<String, String> commands() {
        StatefulRedisConnection<String, String> local = connection;
        if (local == null) {
            synchronized (this) {
                local = connection;
                if (local == null) {
                    client = RedisClient.create(RedisURI.create(address));
                    local = client.connect();
                    connection = local;
                }
            }
        }
        return local.sync();
    }

    @PreDestroy
    void shutdown() {
        StatefulRedisConnection<String, String> localConnection = connection;
        if (localConnection != null) {
            localConnection.close();
        }
        RedisClient localClient = client;
        if (localClient != null) {
            localClient.shutdown();
        }
    }
}

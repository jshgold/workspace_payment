package com.payment.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.product.infrastructure.RedisSpinLockExecutor;
import com.payment.support.IntegrationTestSupport;
import com.payment.support.RedisCommandStats;
import com.payment.support.WithRedis;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * 소유자 토큰 확인 없이 락을 해제하면 남의 락을 지우는 것을 보여주는 실험.
 * <p>
 * 시나리오(한 스레드로 순서를 고정):
 * <ol>
 *   <li>A가 leaseTime 300ms로 락을 얻는다.</li>
 *   <li>A의 작업이 오래 걸려 락이 만료된다. (400ms 대기)</li>
 *   <li>B가 같은 락을 얻는다. (B는 아직 작업 중)</li>
 *   <li>A가 작업을 마치고 락을 해제한다. ← 여기서 방식에 따라 갈린다.</li>
 *   <li>C가 락을 얻으려 한다.</li>
 * </ol>
 * 토큰 확인(Lua) 해제는 A의 해제가 무시되어 C가 못 들어오고, 토큰 없는 DEL은 B의 락이 지워져 C가 B와 동시에 들어온다.
 */
@WithRedis
@Tag("concurrency")
class RedisSpinLockOwnerTokenTest extends IntegrationTestSupport {

    private static final String LOCK = "spinlock:owner-token-test";

    @Autowired
    private RedisSpinLockExecutor redisSpinLockExecutor;

    @Value("${app.redis.address}")
    private String redisAddress;

    private RedisCommandStats redis;

    @BeforeEach
    void connect() {
        redis = new RedisCommandStats(redisAddress);
        redis.delete(LOCK);
    }

    @AfterEach
    void cleanUp() {
        redis.delete(LOCK);
        redis.close();
    }

    @Test
    @DisplayName("토큰을 확인해 해제하면 만료된 A의 해제가 B의 락을 지우지 않아 C는 락을 얻지 못한다")
    void releaseWithOwnerCheckKeepsOthersLock() throws InterruptedException {
        // given
        String tokenA = redisSpinLockExecutor.tryAcquire(LOCK, 1_000, 300);
        Thread.sleep(400);
        String tokenB = redisSpinLockExecutor.tryAcquire(LOCK, 1_000, 5_000);

        // when
        boolean releasedByA = redisSpinLockExecutor.release(LOCK, tokenA);
        String tokenC = redisSpinLockExecutor.tryAcquire(LOCK, 100, 5_000);

        // then
        assertThat(tokenA).isNotNull();
        assertThat(tokenB).isNotNull();
        assertThat(releasedByA).isFalse();
        assertThat(redis.exists(LOCK)).isTrue();
        assertThat(tokenC).isNull();
    }

    @Test
    @DisplayName("토큰 확인 없이 DEL로 해제하면 A의 해제가 B의 락을 지워 C가 B와 동시에 임계구역에 들어온다")
    void releaseWithoutOwnerCheckDeletesOthersLock() throws InterruptedException {
        // given
        String tokenA = redisSpinLockExecutor.tryAcquire(LOCK, 1_000, 300);
        Thread.sleep(400);
        String tokenB = redisSpinLockExecutor.tryAcquire(LOCK, 1_000, 5_000);

        // when
        redis.delete(LOCK);
        String tokenC = redisSpinLockExecutor.tryAcquire(LOCK, 100, 5_000);

        // then
        assertThat(tokenA).isNotNull();
        assertThat(tokenB).isNotNull();
        assertThat(tokenC).isNotNull();
    }
}

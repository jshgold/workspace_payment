package com.payment.product.infrastructure;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Redis 분산 락(Redisson {@link RLock}) 실행기.
 * <p>
 * Named Lock과 달리 락이 DB 커넥션을 쓰지 않는다. 락 대기자가 아무리 많아도 DB 풀을 차지하지 않으므로
 * 풀 데드락이 구조적으로 없다. 대신 Redis라는 별도 시스템에 락이 있어서 DB 트랜잭션과의 순서는 코드가 보장해야 한다.
 * <ul>
 *   <li>{@code waitTime}: 락을 얻기 위해 기다리는 최대 시간. 넘으면 획득 실패.</li>
 *   <li>{@code leaseTime}: 락을 쥐고 있을 수 있는 최대 시간. 넘으면 Redis가 락을 자동으로 푼다.
 *       (서버가 죽어도 락이 영원히 남지 않게 하는 안전장치. 로직이 이보다 오래 걸리면 상호 배제가 깨진다.)</li>
 * </ul>
 * Redisson 클라이언트는 첫 사용 시점에 만든다. Redis를 쓰지 않는 기능과 테스트는 Redis 없이도 컨텍스트가 뜬다.
 * 이 클래스를 지우면 Redis 락 관련 설정도 같이 사라진다.
 */
@Slf4j
@Component
public class RedisLockExecutor {

    private final String address;
    private volatile RedissonClient client;

    /** 실험 기록용: leaseTime이 지나 락이 이미 풀린 뒤에 해제하려던 횟수 */
    private final AtomicLong leaseExpiredCount = new AtomicLong();

    public RedisLockExecutor(@Value("${app.redis.address:redis://localhost:6379}") String address) {
        this.address = address;
    }

    /**
     * 락 획득 → 작업 실행 → 락 해제 순서를 보장한다.
     * 작업이 끝난(트랜잭션이 커밋된) 뒤에 락을 풀기 때문에, 다음 대기자는 커밋된 최신 상태를 읽는다.
     */
    public void executeWithLock(String lockName, long waitTimeMs, long leaseTimeMs, Runnable action) {
        RLock lock = client().getLock(lockName);
        boolean acquired;
        try {
            acquired = lock.tryLock(waitTimeMs, leaseTimeMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.LOCK_ACQUISITION_FAILED);
        }
        if (!acquired) {
            log.warn("Redis 락 획득 실패(waitTime 초과). name={}, waitTime={}ms", lockName, waitTimeMs);
            throw new BusinessException(ErrorCode.LOCK_ACQUISITION_FAILED);
        }
        try {
            action.run();
        } finally {
            unlockIfHeld(lock, lockName);
        }
    }

    public long leaseExpiredCount() {
        return leaseExpiredCount.get();
    }

    public void resetLeaseExpiredCount() {
        leaseExpiredCount.set(0);
    }

    /** 내가 쥔 락만 푼다. leaseTime이 지나 이미 풀렸다면 남의 락을 건드리지 않고 기록만 남긴다. */
    private void unlockIfHeld(RLock lock, String lockName) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                return;
            }
        } catch (IllegalMonitorStateException e) {
            // isHeldByCurrentThread 확인 직후에 leaseTime이 지난 경우
        }
        leaseExpiredCount.incrementAndGet();
        log.warn("Redis 락이 해제 전에 leaseTime으로 만료되었습니다. 상호 배제가 깨졌을 수 있습니다. name={}", lockName);
    }

    private RedissonClient client() {
        RedissonClient local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    Config config = new Config();
                    config.useSingleServer()
                            .setAddress(address)
                            .setConnectionMinimumIdleSize(2);
                    local = Redisson.create(config);
                    client = local;
                }
            }
        }
        return local;
    }

    @PreDestroy
    void shutdown() {
        RedissonClient local = client;
        if (local != null) {
            local.shutdown();
        }
    }
}

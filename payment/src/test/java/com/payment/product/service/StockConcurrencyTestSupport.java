package com.payment.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.payment.product.domain.Product;
import com.payment.product.domain.ProductRepository;
import com.payment.product.domain.ProductStatus;
import com.payment.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 모든 재고 차감 전략이 공유하는 동시성 테스트 틀.
 * <p>
 * 전략별 테스트 클래스는 이 클래스를 상속하고 {@link #strategy()}만 구현하면
 * 동일한 공통 실험 조건(phase-02)으로 비교된다.
 * 테스트 메서드에 @Transactional을 붙이지 않는다. (붙이면 동시성이 재현되지 않음)
 */
@Tag("concurrency")
public abstract class StockConcurrencyTestSupport extends IntegrationTestSupport {

    private static final Logger log = LoggerFactory.getLogger(StockConcurrencyTestSupport.class);
    private static final int THREAD_COUNT = 100;
    protected static final int QUANTITY = 1;

    @Autowired
    protected ProductRepository productRepository;

    /** 비교 대상 전략 구현체 */
    protected abstract StockDecreaseService strategy();

    /** 로그에 찍을 전략 이름 */
    protected abstract String strategyName();

    @AfterEach
    void cleanUp() {
        productRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("재고 100에서 100개 스레드가 1개씩 차감하면 전부 성공하고 최종 재고는 0이며 SOLD_OUT이다")
    void stockEqualsRequests() throws InterruptedException {
        // given
        Long productId = saveProduct(100);

        // when
        Result result = runConcurrently(productId);

        // then
        Product found = productRepository.findById(productId).orElseThrow();
        log(result, found, "재고100/요청100");
        assertThat(result.success()).isEqualTo(100);
        assertThat(result.fail()).isZero();
        assertThat(found.getStock()).isZero();
        assertThat(found.getStatus()).isEqualTo(ProductStatus.SOLD_OUT);
    }

    @Test
    @DisplayName("재고 50에서 100개 스레드가 1개씩 차감하면 성공 50, 실패 50이고 최종 재고는 0이다")
    void stockLessThanRequests() throws InterruptedException {
        // given
        Long productId = saveProduct(50);

        // when
        Result result = runConcurrently(productId);

        // then
        Product found = productRepository.findById(productId).orElseThrow();
        log(result, found, "재고50/요청100");
        assertThat(result.success()).isEqualTo(50);
        assertThat(result.fail()).isEqualTo(50);
        assertThat(found.getStock()).isZero();
        assertThat(result.success() * QUANTITY + found.getStock()).isEqualTo(50);
    }

    protected Long saveProduct(int stock) {
        return productRepository.save(Product.create("동시성 테스트 상품", 10_000, stock)).getId();
    }

    protected Result runConcurrently(Long productId) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREAD_COUNT);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    strategy().decrease(productId, QUANTITY);
                    success.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail.incrementAndGet();
                } catch (Exception e) {
                    fail.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        long begin = System.nanoTime();
        start.countDown();
        done.await();
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000;
        executor.shutdown();

        return new Result(success.get(), fail.get(), elapsedMs);
    }

    protected void log(Result result, Product found, String scenario) {
        log.info("[{}] {} → 성공 {}, 실패 {}, 최종 재고 {}, 소요 {}ms",
                strategyName(), scenario, result.success(), result.fail(), found.getStock(), result.elapsedMs());
    }

    protected record Result(int success, int fail, long elapsedMs) {
    }
}

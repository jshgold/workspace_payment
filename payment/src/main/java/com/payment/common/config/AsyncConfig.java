package com.payment.common.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 기본 executor에 맡기지 않고 결제 이벤트 후처리 전용 스레드 풀을 둔다. (ARCHITECTURE 5-1) */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "paymentEventExecutor")
    public Executor paymentEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("payment-event-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        // 큐가 가득 차도 후처리를 버리지 않고, 발행한 스레드가 직접 실행해 속도를 늦춘다.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}

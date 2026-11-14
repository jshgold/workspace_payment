package com.payment.support;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * {@link WithRedis}가 붙은 테스트에서만 Redis 컨테이너를 띄우고, 접속 주소를 {@code app.redis.address}로 주입한다.
 * 컨테이너는 JVM 당 한 번만 시작해서 Redis 테스트 클래스들이 공유한다. (종료는 Testcontainers의 Ryuk가 담당)
 * Redis를 쓰지 않는 테스트는 이 클래스를 거치지 않으므로 Redis 컨테이너가 뜨지 않는다.
 */
public class RedisContainerInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final class Holder {
        static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7"))
                .withExposedPorts(6379);

        static {
            REDIS.start();
        }
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        String address = "redis://" + Holder.REDIS.getHost() + ":" + Holder.REDIS.getMappedPort(6379);
        TestPropertyValues.of("app.redis.address=" + address).applyTo(context);
    }
}

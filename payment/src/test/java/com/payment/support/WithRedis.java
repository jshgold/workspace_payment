package com.payment.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.test.context.ContextConfiguration;

/**
 * 이 테스트는 Redis 컨테이너가 필요하다. (Testcontainers)
 * 상속 구조와 무관하게 붙일 수 있어서, 이미 {@code IntegrationTestSupport}를 상속한 테스트 틀에도 쓸 수 있다.
 */
@Documented
@Inherited
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ContextConfiguration(initializers = RedisContainerInitializer.class)
public @interface WithRedis {
}

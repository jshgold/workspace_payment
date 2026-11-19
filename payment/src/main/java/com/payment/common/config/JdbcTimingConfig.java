package com.payment.common.config;

import javax.sql.DataSource;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * [측정 전용 계측] {@code payment.timing} 로거가 DEBUG일 때만 DataSource를 {@link JdbcTimingDataSource}로 감싼다.
 * 꺼져 있으면(기본값) 원래 DataSource를 그대로 쓴다. 켜는 법: {@code --logging.level.payment.timing=debug}
 */
@Configuration(proxyBeanMethods = false)
public class JdbcTimingConfig {

    @Bean
    static BeanPostProcessor jdbcTimingDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource
                        && !(bean instanceof JdbcTimingDataSource)
                        && LoggerFactory.getLogger("payment.timing").isDebugEnabled()) {
                    return new JdbcTimingDataSource(dataSource);
                }
                return bean;
            }
        };
    }
}

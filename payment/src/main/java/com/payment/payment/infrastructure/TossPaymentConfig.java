package com.payment.payment.infrastructure;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** {@code toss} 일 때만 프로퍼티 바인딩·검증과 HTTP 클라이언트를 켠다. (fake 모드에서는 키가 없어도 기동되어야 한다) */
@Configuration
@ConditionalOnProperty(name = "app.pg.provider", havingValue = "toss")
@EnableConfigurationProperties(TossPaymentProperties.class)
public class TossPaymentConfig {

    /**
     * 연결·읽기 타임아웃을 명시한 토스 전용 RestClient. 타임아웃을 두지 않으면 PG가 응답하지 않을 때
     * 호출 스레드가 무기한 묶인다.
     * <p>
     * 인증은 문서대로 {@code Authorization: Basic base64(시크릿키:)} 이고, 키 뒤의 콜론을 빠뜨리면 인증에 실패한다.
     */
    @Bean
    public RestClient tossRestClient(TossPaymentProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        String credentials = Base64.getEncoder()
                .encodeToString((properties.secretKey() + ":").getBytes(StandardCharsets.UTF_8));

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                .build();
    }
}

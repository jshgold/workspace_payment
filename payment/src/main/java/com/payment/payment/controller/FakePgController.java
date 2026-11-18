package com.payment.payment.controller;

import com.payment.payment.dto.FakePgStatsResponse;
import com.payment.payment.infrastructure.FakePaymentClient;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * [측정 전용] 가짜 PG 모드에서만 등록된다. 실제 PG(toss) 모드에서는 빈 자체가 없다.
 * <p>
 * HTTP 응답만으로는 "경합에서 진 요청이 PG 승인·취소까지 갔는지"가 보이지 않으므로,
 * k6 같은 외부 부하 도구가 PG 호출 횟수를 직접 읽을 수 있게 노출한다.
 * 비즈니스 흐름이 아니라 테스트 대역의 계측값이라 Service를 거치지 않는다.
 */
@RestController
@RequestMapping("/api/fake-pg")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.pg.provider", havingValue = "fake", matchIfMissing = true)
public class FakePgController {

    private final FakePaymentClient fakePaymentClient;

    @GetMapping("/stats")
    public ResponseEntity<FakePgStatsResponse> stats() {
        return ResponseEntity.ok(new FakePgStatsResponse(
                fakePaymentClient.approveCallCount(), fakePaymentClient.cancelCallCount()));
    }

    /** 호출 횟수와 시나리오를 초기화한다. 지연은 {@code app.pg.fake.delay} 값으로 돌아간다. */
    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        fakePaymentClient.reset();
        return ResponseEntity.noContent().build();
    }
}

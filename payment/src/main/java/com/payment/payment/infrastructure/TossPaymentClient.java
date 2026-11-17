package com.payment.payment.infrastructure;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.payment.payment.domain.PaymentClient;
import com.payment.payment.domain.PgApproveRequest;
import com.payment.payment.domain.PgApproveResult;

import lombok.RequiredArgsConstructor;

/**
 * 토스페이먼츠 실제 연동 구현체. {@code app.pg.provider=toss}(= {@code toss} 프로필)일 때만 빈으로 등록된다.
 * <p>
 * 4-1에서는 설정 주입과 구현체 전환만 확인한다. 실제 HTTP 호출은 공식 문서로 요청·응답 형식을 확인한 뒤
 * 4-2(승인), 4-3(취소)에서 채운다.
 */
@Component
@ConditionalOnProperty(name = "app.pg.provider", havingValue = "toss")
@RequiredArgsConstructor
public class TossPaymentClient implements PaymentClient {

    private final TossPaymentProperties properties;

    @Override
    public PgApproveResult approve(PgApproveRequest request) {
        throw new UnsupportedOperationException("토스 승인 연동은 phase-04 4-2에서 구현한다.");
    }

    @Override
    public void cancel(String pgTransactionId, String reason) {
        throw new UnsupportedOperationException("토스 취소 연동은 phase-04 4-3에서 구현한다.");
    }
}

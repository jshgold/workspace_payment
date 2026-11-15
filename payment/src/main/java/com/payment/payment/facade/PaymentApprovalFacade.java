package com.payment.payment.facade;

import java.util.Optional;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.payment.domain.PaymentCancelFailedException;
import com.payment.payment.domain.PaymentTimeoutException;
import com.payment.payment.dto.PaymentResponse;
import com.payment.payment.service.IdempotencyService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 멱등키를 앞뒤에 두르는 결제 승인 진입점. 이 클래스에도 {@code @Transactional}이 없다.
 * <pre>
 * 1. begin     (짧은 트랜잭션, 주문 행 락) 키 선점 또는 같은 키의 재요청 응답
 * 2. PaymentFacade.approve                   주문 선점 → PG 호출 → 결과 반영 → 실패 시 보상
 * 3. succeed / fail (짧은 트랜잭션)          첫 요청의 결과를 키에 기록
 * </pre>
 * 키에 기록하는 기준은 "돈이 나갔는지 아는가"다. 타임아웃처럼 결과를 모르는 실패는 확정 실패로 기록하지 않고
 * IN_PROGRESS로 남긴다. 그래야 재요청이 "실패"를 받고 새로 결제하는 이중 결제를 막을 수 있다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentApprovalFacade {

    private final IdempotencyService idempotencyService;
    private final PaymentFacade paymentFacade;

    public PaymentResponse approve(Long orderId, String idempotencyKey, long requestedAmount) {
        Optional<PaymentResponse> replayed = idempotencyService.begin(idempotencyKey, orderId, requestedAmount);
        if (replayed.isPresent()) {
            return replayed.get();
        }

        try {
            PaymentResponse response = paymentFacade.approve(orderId);
            recordSuccess(idempotencyKey);
            return response;
        } catch (PaymentTimeoutException | PaymentCancelFailedException | DataIntegrityViolationException e) {
            // 돈이 나갔는지 모른다. 확정 실패로 기록하지 않고 IN_PROGRESS로 남겨 재요청이 "처리 중"을 받게 한다.
            throw e;
        } catch (BusinessException e) {
            idempotencyService.fail(idempotencyKey, e.getErrorCode());
            throw e;
        } catch (IllegalStateException e) {
            // 주문 상태가 맞지 않거나 재고 부족처럼, 돈이 나가지 않은 것이 확정된 실패다.
            idempotencyService.fail(idempotencyKey, ErrorCode.STATE_CONFLICT);
            throw e;
        }
        // 그 밖의 예상 못 한 예외도 결과를 모르므로 IN_PROGRESS로 남긴다.
    }

    /** 결제는 이미 끝났으므로, 키 기록이 실패해도 사용자에게 실패로 응답하지 않고 로그로 남긴다. */
    private void recordSuccess(String idempotencyKey) {
        try {
            idempotencyService.succeed(idempotencyKey);
        } catch (RuntimeException e) {
            log.error("결제는 완료됐으나 멱등키 성공 기록에 실패했다. 재요청이 계속 '처리 중'을 받는다. key={}", idempotencyKey, e);
        }
    }
}

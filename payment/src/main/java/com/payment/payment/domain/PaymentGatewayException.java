package com.payment.payment.domain;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;

/**
 * 우리 쪽 연동 문제(잘못된 시크릿 키, 허용되지 않은 IP 등)로 PG가 요청 자체를 받아주지 않았다.
 * 승인 단계까지 가지 못했으므로 돈은 나가지 않았다. 구매자의 "카드 거절"이 아니라 운영자가 고쳐야 하는 문제라서
 * {@link PaymentDeclinedException}과 구분한다. (둘 다 주문은 PENDING으로 복구한다)
 */
public class PaymentGatewayException extends BusinessException {

    public PaymentGatewayException(String pgErrorCode) {
        super(ErrorCode.PAYMENT_GATEWAY_ERROR, ErrorCode.PAYMENT_GATEWAY_ERROR.getMessage() + " (PG 코드: " + pgErrorCode + ")");
    }
}

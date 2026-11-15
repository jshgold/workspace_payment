package com.payment.payment.domain;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;

/** PG가 결제를 명확히 거절했다. 결과가 확정된 실패다. */
public class PaymentDeclinedException extends BusinessException {

    public PaymentDeclinedException(String reason) {
        super(ErrorCode.PAYMENT_DECLINED, "결제가 거절되었습니다. 사유: " + reason);
    }
}

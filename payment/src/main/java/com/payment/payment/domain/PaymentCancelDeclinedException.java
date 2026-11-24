package com.payment.payment.domain;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;

/** PG가 취소를 명확히 거절했다. 결제는 그대로 살아 있다. (취소되지 않았음이 확정된 실패) */
public class PaymentCancelDeclinedException extends BusinessException {

    public PaymentCancelDeclinedException(String pgErrorCode, String reason) {
        super(ErrorCode.PAYMENT_CANCEL_DECLINED,
                ErrorCode.PAYMENT_CANCEL_DECLINED.getMessage() + " 사유: " + reason + " (PG 코드: " + pgErrorCode + ")");
    }
}

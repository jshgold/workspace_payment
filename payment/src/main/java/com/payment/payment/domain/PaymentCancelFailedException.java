package com.payment.payment.domain;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;

public class PaymentCancelFailedException extends BusinessException {

    public PaymentCancelFailedException(String pgTransactionId) {
        super(ErrorCode.PAYMENT_CANCEL_FAILED, "결제 취소에 실패했습니다. pgTransactionId=" + pgTransactionId);
    }
}

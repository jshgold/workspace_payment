package com.payment.payment.domain;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;

/** PG 응답을 받지 못했다. 승인 여부가 <b>불확실</b>한 실패라서 거절과 다르게 다뤄야 한다. */
public class PaymentTimeoutException extends BusinessException {

    public PaymentTimeoutException() {
        super(ErrorCode.PAYMENT_TIMEOUT);
    }
}

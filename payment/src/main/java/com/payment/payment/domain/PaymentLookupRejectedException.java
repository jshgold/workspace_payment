package com.payment.payment.domain;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;

/**
 * PG 결제 조회가 우리 요청 자체를 거절했다 (일시 오류·키 오류가 아닌 4xx. 예: 일반 404 NOT_FOUND, 400).
 * 같은 요청을 다시 보내도 같은 응답이 오므로 일시 실패(조회 실패)가 아니다. 정상 흐름에서는 생기지 않으며,
 * 생기면 우리 쪽 데이터·버그를 사람이 봐야 한다. 대사는 그 건만 건너뛰고 연속 실패 횟수에 세지 않는다. (intent 8절 대사 구현 세부 ⑤)
 */
public class PaymentLookupRejectedException extends BusinessException {

    public PaymentLookupRejectedException(int status, String code) {
        super(ErrorCode.PAYMENT_GATEWAY_ERROR, "결제 조회 요청이 거절되었습니다. status=" + status + ", code=" + code);
    }
}

package com.payment.payment.service;

import com.payment.common.exception.ErrorCode;
import com.payment.payment.dto.PaymentResponse;

/**
 * 결제 시작 트랜잭션의 결과. 거절도 예외가 아니라 값으로 돌려준다.
 * PG 호출 전 거절은 실패 기록을 <b>커밋</b>해야 하므로, 트랜잭션 안에서 예외를 던져 롤백시키면 안 되기 때문이다.
 */
public sealed interface StartPayingResult {

    /** 주문 선점, 재고 차감, 시도 기록(REQUESTED)을 마쳤다. PG 승인을 호출한다. */
    record Proceed(PaymentTarget target) implements StartPayingResult {
    }

    /** 같은 키로 이미 승인된 시도의 재전송이다. 첫 결과를 그대로 돌려준다. */
    record Replayed(PaymentResponse response) implements StartPayingResult {
    }

    /** PG를 부르기 전에 거절했고, 주문 FAILED와 Payment FAILED를 기록했다. */
    record Rejected(ErrorCode errorCode) implements StartPayingResult {
    }
}

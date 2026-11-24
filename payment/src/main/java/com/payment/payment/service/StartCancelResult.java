package com.payment.payment.service;

import com.payment.payment.dto.PaymentResponse;

/**
 * 취소 시작의 결과. 거절(처리 중, 이미 취소됨, 키 재사용 등)은 결과가 아니라 {@code BusinessException}으로 던진다.
 */
public sealed interface StartCancelResult {

    /** 같은 키의 재전송이고 첫 시도가 성공했다. 저장된 결과를 돌려준다. PG를 부르지 않는다. */
    record Replayed(PaymentResponse response) implements StartCancelResult {
    }

    /** 새 시도를 선점했다. PG 취소를 호출한다. */
    record Proceed(CancelTarget target) implements StartCancelResult {
    }
}

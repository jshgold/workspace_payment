package com.payment.order.service;

import org.springframework.stereotype.Service;

/**
 * 결제를 시작하지 않은 채 버려진 주문을 만료한다. PG를 부르지 않고 DB만 바꾼다. (phase-04 4-4)
 * <p>
 * 대상 id를 락 없이 고른 뒤 주문마다 {@code FOR UPDATE}로 잠그고 {@code PENDING}인지 다시 확인해
 * 도메인 메서드로 {@code EXPIRED}로 바꾼다. 결제 시작({@code PENDING → PAYING})과 겹쳐도 한쪽만 반영된다.
 * <p>
 * 지금은 재현 테스트를 컴파일하기 위한 뼈대다. 본문은 구현 단계에서 작성한다.
 */
@Service
public class OrderExpirationService {

    public void expire() {
        throw new UnsupportedOperationException("주문 만료는 phase-04 4-4 구현 단계에서 작성한다.");
    }
}

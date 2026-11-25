package com.payment.payment.facade;

import org.springframework.stereotype.Service;

/**
 * 결제·취소 대사. 이 클래스에는 {@code @Transactional}이 없다. (phase-04 4-4)
 * <pre>
 * 1. 대상 id 조회 (락 없음)      상태가 된 지(updated_at) 기준 시간이 지난 건
 * 2. PG 결제 조회 (트랜잭션 밖)  조회만 한다. 승인 요청을 다시 보내지 않는다
 * 3. 주문마다 짧은 트랜잭션       FOR UPDATE → 현재 상태 재확인 → 도메인 메서드로 전이
 * </pre>
 * 분류 기준은 {@code intent.md} 8절 "승인 대사 분류", "취소 대사·보상 취소 실패 건의 NOT_FOUND_PAYMENT"를 따른다.
 * <p>
 * 지금은 재현 테스트를 컴파일하기 위한 뼈대다. 본문은 구현 단계에서 작성한다.
 */
@Service
public class PaymentReconciliationFacade {

    /** 승인 대사. 대상: {@code PAYMENT_UNKNOWN}, {@code PAYING} (상태가 된 지 기준 시간 경과) */
    public void reconcilePayments() {
        throw new UnsupportedOperationException("승인 대사는 phase-04 4-4 구현 단계에서 작성한다.");
    }

    /** 취소 대사. 대상: {@code CANCEL_UNKNOWN}, {@code CANCELING} (상태가 된 지 기준 시간 경과) */
    public void reconcileCancels() {
        throw new UnsupportedOperationException("취소 대사는 phase-04 4-4 구현 단계에서 작성한다.");
    }
}

package com.payment.product.service;

/**
 * 재고 차감 전략의 공통 계약.
 * <p>
 * 락 없음 / Atomic Update / 비관적 락 / 낙관적 락 / Named Lock / Redis 분산 락을
 * 각각 이 인터페이스의 구현체로 만들고, 동일한 동시성 테스트로 비교한다.
 * <p>
 * 재고가 부족하면 예외를 던진다. (호출자는 예외 발생 = 차감 실패로 센다)
 */
public interface StockDecreaseService {

    void decrease(Long productId, int quantity);
}

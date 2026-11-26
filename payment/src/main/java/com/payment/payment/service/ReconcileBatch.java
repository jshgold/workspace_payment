package com.payment.payment.service;

import java.util.List;

/**
 * 대사 대상 한 묶음.
 *
 * @param targets       처리할 대상
 * @param selectedCount 이번에 고른 주문 수 (대상으로 만들지 못한 건 포함). 묶음 크기보다 작으면 마지막 묶음이다
 * @param lastId        고른 주문 중 가장 큰 id. 다음 묶음은 이 id 다음부터 고른다. 고른 게 없으면 null
 */
public record ReconcileBatch<T>(List<T> targets, int selectedCount, Long lastId) {
}

package com.payment.order.service;

import java.time.LocalDateTime;
import java.util.List;

import com.payment.common.exception.BusinessException;
import com.payment.common.exception.ErrorCode;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderRepository;
import com.payment.order.dto.CreateOrderRequest;
import com.payment.order.dto.OrderResponse;
import com.payment.product.domain.Product;
import com.payment.product.domain.ProductRepository;

import com.payment.order.domain.OrderStatus;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    @Transactional
    public OrderResponse create(CreateOrderRequest request) {
        Product product = productRepository.findById(request.productId())
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        Order order = Order.create(request.memberId(), product, request.quantity());
        return OrderResponse.from(orderRepository.save(order));
    }

    /**
     * 만료 대상 id를 락 없이 고른다. PENDING은 들어오는 간선이 없어 {@code updated_at} = 주문 생성 시각이므로,
     * 인덱스 {@code (status, updated_at)}를 그대로 쓴다. (4-4)
     */
    @Transactional(readOnly = true)
    public List<Long> findExpirationTargetIds(LocalDateTime before, long afterId, int size) {
        return orderRepository.findIdsForBatch(List.of(OrderStatus.PENDING), before, afterId, Limit.of(size));
    }

    /**
     * 주문 하나를 잠그고 아직 PENDING이면 만료한다. 결제 시작({@code PENDING → PAYING})도 같은 주문 락을 잡으므로
     * 둘이 겹치면 먼저 잡은 쪽만 반영되고 뒤에 온 쪽은 바뀐 상태를 본다.
     *
     * @return 만료했으면 true, 그사이 결제가 시작돼 PENDING이 아니면 false
     */
    @Transactional
    public boolean expireIfPending(Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
        if (order.getStatus() != OrderStatus.PENDING) {
            return false;
        }
        order.expire();
        return true;
    }
}

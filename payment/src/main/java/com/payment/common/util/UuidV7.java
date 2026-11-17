package com.payment.common.util;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * UUIDv7(RFC 9562) 생성기. 앞 48비트가 밀리초 타임스탬프라서 생성 순서대로 정렬되고,
 * 나머지는 난수라서 외부에서 추측할 수 없다.
 * <p>
 * 같은 밀리초 안에서의 정렬은 보장하지 않는다. (단조 증가 카운터는 쓰지 않는다)
 * 주문번호처럼 "유일하고 추측 불가능하며 인덱스 삽입이 대체로 뒤쪽에 쌓이는" 용도로만 쓴다.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate() {
        return generate(System.currentTimeMillis());
    }

    /** 테스트에서 시각을 고정하기 위한 진입점. */
    static UUID generate(long epochMillis) {
        long randA = RANDOM.nextLong() & 0x0FFFL;
        long msb = (epochMillis << 16) | 0x7000L | randA;
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }
}

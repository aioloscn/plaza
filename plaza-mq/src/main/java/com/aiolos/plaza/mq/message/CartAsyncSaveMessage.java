package com.aiolos.plaza.mq.message;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 购物车异步落库消息
 */
public record CartAsyncSaveMessage(
        Long userId,
        Long shopId,
        Long productId,
        Integer quantity,
        Integer checked,
        BigDecimal priceSnapshot,
        String productName,
        String productImage,
        Integer status,
        /**
         * 操作类型 1:保存/更新(默认) 2:删除
         */
        Integer operateType
) implements Serializable {
    public CartAsyncSaveMessage {
        if (operateType == null) {
            operateType = 1;
        }
    }
}

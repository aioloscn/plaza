package com.aiolos.plaza.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 商品缓存删除消息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductCacheDeleteMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 商品 ID
     */
    private Long productId;
}

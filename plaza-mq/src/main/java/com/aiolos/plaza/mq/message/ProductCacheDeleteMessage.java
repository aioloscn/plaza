package com.aiolos.plaza.mq.message;

import java.io.Serializable;

/**
 * 商品缓存删除消息
 *
 * @param productId 商品 ID
 */
public record ProductCacheDeleteMessage(Long productId) implements Serializable {
}

package com.aiolos.plaza.mq.message;

import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 购物车异步落库消息
 */
@Data
public class CartAsyncSaveMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private Long userId;
    private Long shopId;
    private Long productId;
    private Integer quantity;
    private Integer checked;
    private BigDecimal priceSnapshot;
    private String productName;
    private String productImage;
    private Integer status;
}

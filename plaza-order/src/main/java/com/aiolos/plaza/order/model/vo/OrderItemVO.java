package com.aiolos.plaza.order.model.vo;

import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class OrderItemVO implements Serializable {
    private Long id;
    private Long productId;
    private String productName;
    private String productPic;
    private BigDecimal productPrice;
    private Integer productQuantity;
    private String productAttr;
}

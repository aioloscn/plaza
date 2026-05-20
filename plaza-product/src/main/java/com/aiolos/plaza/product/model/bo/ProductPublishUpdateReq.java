package com.aiolos.plaza.product.model.bo;

import java.io.Serializable;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class ProductPublishUpdateReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 发布关系ID
     */
    private Long publishId;

    /**
     * 场景销售价，空表示回退到SKU基础销售价
     */
    private BigDecimal channelSalePrice;

    /**
     * 是否清空场景销售价并回退到SKU基础销售价
     */
    private Boolean resetChannelSalePrice;

    /**
     * 销售状态：0-下架，1-上架
     */
    private Integer saleStatus;

    /**
     * 可见状态：0-隐藏，1-可见
     */
    private Integer visibleStatus;

    /**
     * 排序号
     */
    private Integer sortNo;
}

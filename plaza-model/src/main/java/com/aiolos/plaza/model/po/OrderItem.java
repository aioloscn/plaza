package com.aiolos.plaza.model.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * <p>
 * 订单中所包含的商品
 * </p>
 *
 * @author aiolos
 * @since 2026-03-16
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("order_item")
public class OrderItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 订单id
     */
    private Long orderId;

    /**
     * 订单编号
     */
    private String orderSn;

    /**
     * 商品id
     */
    private Long productId;

    /**
     * 商品图片
     */
    private String productPic;

    /**
     * 商品名称
     */
    private String productName;

    /**
     * 商品品牌
     */
    private String productBrand;

    /**
     * 商品条码
     */
    private String productSn;

    /**
     * 销售价格
     */
    private BigDecimal productPrice;

    /**
     * 购买数量
     */
    private Integer productQuantity;

    /**
     * 商品sku编号
     */
    private Long productSkuId;

    /**
     * 商品sku条码
     */
    private String productSkuCode;

    /**
     * 商品分类id
     */
    private Long productCategoryId;

    /**
     * 商品促销名称
     */
    private String promotionName;

    /**
     * 商品促销分解金额
     */
    private BigDecimal promotionAmount;

    /**
     * 优惠券优惠分解金额
     */
    private BigDecimal couponAmount;

    /**
     * 积分优惠分解金额
     */
    private BigDecimal integrationAmount;

    /**
     * 该商品经过优惠后的分解金额
     */
    private BigDecimal realAmount;

    /**
     * 商品赠送积分
     */
    private Integer giftIntegration;

    /**
     * 商品赠送成长值
     */
    private Integer giftGrowth;

    /**
     * 商品销售属性:[{"key":"颜色","value":"颜色"},{"key":"容量","value":"4G"}]
     */
    private String productAttr;

}

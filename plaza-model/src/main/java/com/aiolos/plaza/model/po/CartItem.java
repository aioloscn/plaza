package com.aiolos.plaza.model.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 购物车项实体类
 */
@Data
@TableName("cart_item")
public class CartItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 店铺ID
     */
    private Long shopId;

    /**
     * 商品SKU ID
     */
    @TableField("sku_id")
    private Long skuId;

    /**
     * 业务类型 1:外卖/即时零售 2:电商
     */
    private Integer bizType;

    /**
     * 数量
     */
    private Integer quantity;

    /**
     * 是否选中 0:否 1:是
     */
    private Integer checked;

    /**
     * 加入时价格
     */
    private BigDecimal priceSnapshot;

    /**
     * 商品名称
     */
    private String productName;

    /**
     * 商品图片
     */
    private String productImage;

    /**
     * 状态 0:失效 1:正常
     */
    private Integer status;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    public Long getProductId() {
        return skuId;
    }

    public void setProductId(Long productId) {
        this.skuId = productId;
    }
}

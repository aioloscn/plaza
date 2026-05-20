package com.aiolos.plaza.model.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>
 * 库存操作记录表
 * </p>
 *
 * @author Aiolos
 */
@Getter
@Setter
@TableName("product_stock_log")
public class ProductStockLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 商品SKU ID
     */
    @TableField("sku_id")
    private Long skuId;

    /**
     * 关联订单号
     */
    @TableField("order_sn")
    private String orderSn;

    /**
     * 库存池范围：1-普通库存池，2-秒杀库存池
     */
    @TableField("stock_scope")
    private Integer stockScope;

    /**
     * 秒杀活动ID（普通库存池为空）
     */
    @TableField("activity_id")
    private Long activityId;

    /**
     * 变动数量（正数为增加，负数为扣减）
     */
    @TableField("amount")
    private Integer amount;

    /**
     * 操作类型：1-下单扣减，2-取消回滚，3-后台修改，4-预占冻结，5-支付确认，6-预占释放，7-预占过期
     */
    @TableField("type")
    private Integer type;

    /**
     * 创建时间
     */
    @TableField("create_time")
    private LocalDateTime createTime;

    public static final String ID = "id";

    public static final String SKU_ID = "sku_id";

    public static final String ORDER_SN = "order_sn";

    public static final String AMOUNT = "amount";

    public static final String TYPE = "type";

    public static final String CREATE_TIME = "create_time";
}

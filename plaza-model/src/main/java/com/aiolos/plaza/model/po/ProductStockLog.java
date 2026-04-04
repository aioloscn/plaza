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
     * 商品ID
     */
    @TableField("product_id")
    private Long productId;

    /**
     * 关联订单号
     */
    @TableField("order_sn")
    private String orderSn;

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

    public static final String PRODUCT_ID = "product_id";

    public static final String ORDER_SN = "order_sn";

    public static final String AMOUNT = "amount";

    public static final String TYPE = "type";

    public static final String CREATE_TIME = "create_time";
}

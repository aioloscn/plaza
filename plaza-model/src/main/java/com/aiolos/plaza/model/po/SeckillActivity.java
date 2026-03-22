package com.aiolos.plaza.model.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>
 * 秒杀活动表
 * </p>
 */
@Getter
@Setter
@TableName("seckill_activity")
public class SeckillActivity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 店铺ID
     */
    @TableField("shop_id")
    private Long shopId;

    /**
     * 商品ID
     */
    @TableField("product_id")
    private Long productId;

    /**
     * 秒杀价
     */
    @TableField("price")
    private BigDecimal price;

    /**
     * 秒杀总库存
     */
    @TableField("stock")
    private Integer stock;

    /**
     * 开始时间
     */
    @TableField("start_time")
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    @TableField("end_time")
    private LocalDateTime endTime;

    /**
     * 状态 0:未开始 1:进行中 2:已结束
     */
    @TableField("status")
    private Integer status;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    public static final String ID = "id";

    public static final String SHOP_ID = "shop_id";

    public static final String PRODUCT_ID = "product_id";

    public static final String PRICE = "price";

    public static final String STOCK = "stock";

    public static final String START_TIME = "start_time";

    public static final String END_TIME = "end_time";

    public static final String STATUS = "status";

    public static final String CREATE_TIME = "create_time";

    public static final String UPDATE_TIME = "update_time";
}

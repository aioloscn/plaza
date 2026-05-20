package com.aiolos.plaza.model.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("product_ladder_price")
public class ProductLadderPrice implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("sku_id")
    private Long skuId;

    @TableField("biz_type")
    private Integer bizType;

    @TableField("min_quantity")
    private Integer minQuantity;

    @TableField("max_quantity")
    private Integer maxQuantity;

    @TableField("ladder_price")
    private BigDecimal ladderPrice;

    @TableField("status")
    private Integer status;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}

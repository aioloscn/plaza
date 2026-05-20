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
@TableName("product_weight_rule")
public class ProductWeightRule implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("sku_id")
    private Long skuId;

    @TableField("biz_type")
    private Integer bizType;

    @TableField("pricing_weight_type")
    private Integer pricingWeightType;

    @TableField("weight_precision")
    private Integer weightPrecision;

    @TableField("min_weight")
    private BigDecimal minWeight;

    @TableField("max_weight")
    private BigDecimal maxWeight;

    @TableField("step_weight")
    private BigDecimal stepWeight;

    @TableField("rounding_mode")
    private Integer roundingMode;

    @TableField("ext_config_json")
    private String extConfigJson;

    @TableField("status")
    private Integer status;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}

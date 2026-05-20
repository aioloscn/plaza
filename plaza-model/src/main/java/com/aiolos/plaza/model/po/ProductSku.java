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
@TableName("product_sku")
public class ProductSku implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("spu_id")
    private Long spuId;

    @TableField("shop_id")
    private Long shopId;

    @TableField("sku_code")
    private String skuCode;

    @TableField("sku_name")
    private String skuName;

    @TableField("bar_code")
    private String barCode;

    @TableField("market_price")
    private BigDecimal marketPrice;

    @TableField("sale_price")
    private BigDecimal salePrice;

    @TableField("cost_price")
    private BigDecimal costPrice;

    @TableField("total_stock")
    private Integer totalStock;

    @TableField("available_stock")
    private Integer availableStock;

    @TableField("frozen_stock")
    private Integer frozenStock;

    @TableField("status")
    private Integer status;

    @TableField("default_weight")
    private BigDecimal defaultWeight;

    @TableField("weight_unit")
    private String weightUnit;

    @TableField("default_volume")
    private BigDecimal defaultVolume;

    @TableField("volume_unit")
    private String volumeUnit;

    @TableField("image_url")
    private String imageUrl;

    @TableField("ext_config_json")
    private String extConfigJson;

    @TableField(exist = false)
    private String saleAttrJson;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}

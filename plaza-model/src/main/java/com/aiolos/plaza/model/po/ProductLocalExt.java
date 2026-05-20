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
@TableName("product_local_ext")
public class ProductLocalExt implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("spu_id")
    private Long spuId;

    @TableField("packing_fee")
    private BigDecimal packingFee;

    @TableField("unit_name")
    private String unitName;

    @TableField("min_purchase_qty")
    private Integer minPurchaseQty;

    @TableField("max_purchase_qty")
    private Integer maxPurchaseQty;

    @TableField("support_takeaway")
    private Boolean supportTakeaway;

    @TableField("support_self_pickup")
    private Boolean supportSelfPickup;

    @TableField("sale_time_json")
    private String saleTimeJson;

    @TableField("tag_json")
    private String tagJson;

    @TableField("ext_config_json")
    private String extConfigJson;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}

package com.aiolos.plaza.model.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("product_ecommerce_ext")
public class ProductEcommerceExt implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("spu_id")
    private Long spuId;

    @TableField("logistics_template_id")
    private Long logisticsTemplateId;

    @TableField("delivery_origin_province")
    private String deliveryOriginProvince;

    @TableField("delivery_origin_city")
    private String deliveryOriginCity;

    @TableField("delivery_origin_region")
    private String deliveryOriginRegion;

    @TableField("delivery_origin_detail")
    private String deliveryOriginDetail;

    @TableField("after_sale_policy")
    private String afterSalePolicy;

    @TableField("delivery_channel_json")
    private String deliveryChannelJson;

    @TableField("ext_config_json")
    private String extConfigJson;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}

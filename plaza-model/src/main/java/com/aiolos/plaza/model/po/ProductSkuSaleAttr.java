package com.aiolos.plaza.model.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("product_sku_sale_attr")
public class ProductSkuSaleAttr implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("sku_id")
    private Long skuId;

    @TableField("sale_attr_id")
    private Long saleAttrId;

    @TableField("attr_name")
    private String attrName;

    @TableField("attr_value")
    private String attrValue;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}

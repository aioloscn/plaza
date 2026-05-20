package com.aiolos.plaza.model.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("product_spu")
public class ProductSpu implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("shop_id")
    private Long shopId;

    @TableField("spu_name")
    private String spuName;

    @TableField("spu_code")
    private String spuCode;

    @TableField("category_id")
    private Long categoryId;

    @TableField("brand_id")
    private Long brandId;

    @TableField("main_image")
    private String mainImage;

    @TableField("album_images")
    private String albumImages;

    @TableField("product_type")
    private Integer productType;

    @TableField("source_type")
    private Integer sourceType;

    @TableField("status")
    private Integer status;

    @TableField("description")
    private String description;

    @TableField("ext_config_json")
    private String extConfigJson;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}

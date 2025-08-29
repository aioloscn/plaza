package com.aiolos.plaza.home.model.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class SearchShopBO {
    
    @Schema(description = "经度")
    private BigDecimal longitude;
    
    @Schema(description = "纬度")
    private BigDecimal latitude;
    
    @Schema(description = "搜索的关键词")
    private String keyword;

    @Schema(description = "分类id")
    private Long categoryId;

    @Schema(description = "排序字段，0或null使用默认综合排序，1使用距离排序")
    private Integer orderBy = 0;
}

package com.aiolos.plaza.home.model.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 用户画像搜索入参
 * 支持地理位置和关键词召回并选择是否启用个性化加权
 */
@Data
public class UserProfileSearchShopBO {

    @Schema(description = "经度")
    private BigDecimal longitude;

    @Schema(description = "纬度")
    private BigDecimal latitude;

    @Schema(description = "搜索关键词")
    private String keyword;

    @Schema(description = "分类ID")
    private Long categoryId;

    @Schema(description = "标签过滤")
    private String tag;

    @Schema(description = "排序策略：0/空=综合，1=距离优先")
    private Integer orderBy = 0;

    @Schema(description = "是否启用个性化画像加权")
    private Boolean profileEnabled = true;
}

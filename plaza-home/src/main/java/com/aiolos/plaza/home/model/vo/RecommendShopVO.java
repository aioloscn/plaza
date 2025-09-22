package com.aiolos.plaza.home.model.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class RecommendShopVO {
    
    private Long id;
    
    private String name;

    private Long categoryId;
    
    private String categoryName;

    private String iconUrl;
    
    private BigDecimal score;

    private Integer perCapitaPrice;

    private BigDecimal longitude;
    
    private BigDecimal latitude;

    private String address;

    private String tags;
    
    private Long sellerId;
    
    private BigDecimal sellerScore;
    
    private Integer sellerDisabledFlag;

    private String distance;
    
    /**
     * 标签聚合数据（仅在第一个记录中包含，用于传递聚合统计信息）
     */
    private List<ShopTagAggrVO> tagAggregations;
}

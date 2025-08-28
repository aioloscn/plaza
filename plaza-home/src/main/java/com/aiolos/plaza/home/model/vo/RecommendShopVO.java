package com.aiolos.plaza.home.model.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalTime;

@Data
public class RecommendShopVO {
    
    private Long id;
    
    private String name;

    private String iconUrl;
    
    private BigDecimal score;

    private Integer perCapitaPrice;

    private BigDecimal longitude;
    
    private BigDecimal latitude;

    private String address;

    private String tags;

    private String distance;
}

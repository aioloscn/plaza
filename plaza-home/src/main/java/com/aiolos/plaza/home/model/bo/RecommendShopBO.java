package com.aiolos.plaza.home.model.bo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class RecommendShopBO {
    
    @Schema(description = "经度")
    private BigDecimal longitude;
    
    @Schema(description = "纬度")
    private BigDecimal latitude;
}

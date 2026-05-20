package com.aiolos.plaza.product.model.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class ProductWeightMetaDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer pricingWeightType;
    private Integer weightPrecision;
    private BigDecimal minWeight;
    private BigDecimal maxWeight;
    private BigDecimal stepWeight;
    private Integer roundingMode;
    private String extConfigJson;
}

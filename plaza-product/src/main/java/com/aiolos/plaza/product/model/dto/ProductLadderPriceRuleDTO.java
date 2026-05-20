package com.aiolos.plaza.product.model.dto;

import java.io.Serializable;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class ProductLadderPriceRuleDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer minQuantity;
    private Integer maxQuantity;
    private BigDecimal ladderPrice;
}

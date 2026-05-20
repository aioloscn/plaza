package com.aiolos.plaza.product.model.dto;

import java.io.Serializable;
import java.util.List;
import lombok.Data;

@Data
public class ProductSaveResultDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 新建后的SPU ID
     */
    private Long spuId;

    /**
     * 新建后的SKU ID列表
     */
    private List<Long> skuIds;
}

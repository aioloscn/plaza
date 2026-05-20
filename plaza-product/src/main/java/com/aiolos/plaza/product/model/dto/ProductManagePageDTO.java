package com.aiolos.plaza.product.model.dto;

import java.io.Serializable;
import java.util.List;
import lombok.Data;

@Data
public class ProductManagePageDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long pageNum;
    private Long pageSize;
    private Long total;
    private List<ProductManageItemDTO> records;

    @Data
    public static class ProductManageItemDTO implements Serializable {
        private static final long serialVersionUID = 1L;

        private Long spuId;
        private Long shopId;
        private String spuName;
        private String spuCode;
        private String mainImage;
        private Integer productType;
        private Integer status;
        private Integer skuCount;
        private List<Integer> bizTypeList;
        private List<String> bizTypeDescList;
    }
}

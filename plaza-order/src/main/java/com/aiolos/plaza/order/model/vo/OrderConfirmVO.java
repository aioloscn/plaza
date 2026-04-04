package com.aiolos.plaza.order.model.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class OrderConfirmVO implements Serializable {
    private Boolean ready;
    private String confirmToken;
    private BigDecimal totalAmount;
    private Integer itemCount;
    private List<ItemResult> items = new ArrayList<>();

    @Data
    public static class ItemResult implements Serializable {
        private Long cartItemId;
        private Long productId;
        private Long shopId;
        private String productName;
        private Integer quantity;
        private BigDecimal cartPrice;
        private BigDecimal currentPrice;
        private Integer availableStock;
        private Boolean valid;
        private String reasonCode;
        private String reasonMsg;
    }
}

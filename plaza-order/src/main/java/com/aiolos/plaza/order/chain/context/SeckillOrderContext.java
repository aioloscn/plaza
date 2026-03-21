package com.aiolos.plaza.order.chain.context;

import com.aiolos.plaza.order.model.bo.SeckillSubmitReq;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
public class SeckillOrderContext extends TradeContext {
    private SeckillSubmitReq req;
    private BigDecimal seckillPrice;
    private boolean success;
}
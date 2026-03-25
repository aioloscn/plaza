package com.aiolos.plaza.order.service;

import com.aiolos.plaza.mq.message.SeckillStockDeductMessage;

public interface SeckillStockDeductService {

    void consume(SeckillStockDeductMessage message);
}

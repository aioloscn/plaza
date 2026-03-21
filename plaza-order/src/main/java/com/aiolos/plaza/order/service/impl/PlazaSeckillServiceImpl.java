package com.aiolos.plaza.order.service.impl;

import com.aiolos.plaza.order.chain.ChainExecutor;
import com.aiolos.plaza.order.chain.ChainHandler;
import com.aiolos.plaza.order.chain.context.SeckillOrderContext;
import com.aiolos.plaza.order.chain.handler.seckill.SeckillFreqLimitHandler;
import com.aiolos.plaza.order.chain.handler.seckill.SeckillMessageSendHandler;
import com.aiolos.plaza.order.chain.handler.seckill.SeckillStockDeductHandler;
import com.aiolos.plaza.order.model.bo.SeckillSubmitReq;
import com.aiolos.plaza.order.service.PlazaSeckillService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Service
public class PlazaSeckillServiceImpl implements PlazaSeckillService {

    @Resource
    private ChainExecutor chainExecutor;

    @Resource
    private SeckillFreqLimitHandler seckillFreqLimitHandler;

    @Resource
    private SeckillStockDeductHandler seckillStockDeductHandler;

    @Resource
    private SeckillMessageSendHandler seckillMessageSendHandler;

    @Override
    public boolean submitSeckill(SeckillSubmitReq req, Long userId) {
        SeckillOrderContext context = new SeckillOrderContext();
        context.setUserId(userId);
        context.setReq(req);
        context.setSuccess(false);

        List<ChainHandler<SeckillOrderContext>> handlers = Arrays.asList(
                seckillFreqLimitHandler,
                seckillStockDeductHandler,
                seckillMessageSendHandler
        );

        chainExecutor.execute(handlers, context);

        return context.isSuccess();
    }
}

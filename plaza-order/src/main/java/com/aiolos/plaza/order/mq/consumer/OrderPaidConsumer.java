package com.aiolos.plaza.order.mq.consumer;

import com.aiolos.plaza.model.po.ParentOrder;
import com.aiolos.plaza.model.po.PaymentLog;
import com.aiolos.plaza.mapper.PaymentLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.function.Consumer;

/**
 * 监听订单支付成功消息
 */
@Slf4j
@Component
public class OrderPaidConsumer {

    @Autowired
    private PaymentLogMapper paymentLogMapper;

    @Bean
    public Consumer<ParentOrder> orderPaid() {
        return parentOrder -> {
            log.info("收到订单支付成功消息，父订单号: {}, 支付金额: {}", parentOrder.getParentOrderSn(), parentOrder.getPayAmount());
            try {
                long exists = paymentLogMapper.selectCount(new LambdaQueryWrapper<PaymentLog>()
                        .eq(PaymentLog::getOrderSn, parentOrder.getParentOrderSn())
                        .eq(PaymentLog::getTradeNo, parentOrder.getTradeNo()));
                if (exists > 0) {
                    log.info("支付流水已存在，跳过重复消费，父订单号: {}, tradeNo: {}", parentOrder.getParentOrderSn(), parentOrder.getTradeNo());
                    return;
                }

                // 记录支付流水日志
                PaymentLog paymentLog = new PaymentLog();
                paymentLog.setOrderSn(parentOrder.getParentOrderSn());
                paymentLog.setPayType(parentOrder.getPayType());
                paymentLog.setTotalAmount(parentOrder.getPayAmount());
                paymentLog.setPaymentTime(parentOrder.getPaymentTime());
                paymentLog.setCreateTime(LocalDateTime.now());
                
                // 完善第三方流水号和买家账号
                paymentLog.setTradeNo(parentOrder.getTradeNo());
                paymentLog.setBuyerId(parentOrder.getBuyerId());

                paymentLogMapper.insert(paymentLog);
                
                log.info("支付流水日志记录成功，流水ID: {}", paymentLog.getId());
            } catch (Exception e) {
                log.error("处理订单支付成功消息异常，父订单号: {}", parentOrder.getParentOrderSn(), e);
                throw new RuntimeException("处理订单支付成功消息异常", e); // 抛出异常触发重试
            }
        };
    }
}

package com.aiolos.plaza.mq.message;

import java.io.Serializable;

/**
 * 支付补偿失败后的退款消息
 * 只携带父订单号，消费端按父订单最新快照执行整笔退款和状态收敛
 */
public record OrderRefundMessage(String parentOrderSn) implements Serializable {
}

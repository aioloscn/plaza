package com.aiolos.plaza.order.workflow.payment;

/**
 * 支付回调场景路由枚举
 * 由场景判定器选出一个分支，再委派给 PaymentNotifySupport 执行
 */
public enum PaymentNotifyScenario {
    // 订单已进入退款链路，支付回调只做幂等确认与必要补偿
    ALREADY_IN_REFUND_FLOW {
        @Override
        public String handle(PaymentNotifyContext context, PaymentNotifySupport support) {
            return support.handleRefundFlowNotify(context);
        }
    },
    // 父单已关闭，回调不能再按正常支付入账
    CLOSED_PARENT {
        @Override
        public String handle(PaymentNotifyContext context, PaymentNotifySupport support) {
            return support.handleClosedParentNotify(context);
        }
    },
    // 第三方流水号与本地已记录流水号冲突，走冲突处理分支
    TRADE_NO_CONFLICT {
        @Override
        public String handle(PaymentNotifyContext context, PaymentNotifySupport support) {
            return support.handleTradeNoConflict(context);
        }
    },
    // 已经处理过支付成功，直接走幂等返回
    ALREADY_PAID {
        @Override
        public String handle(PaymentNotifyContext context, PaymentNotifySupport support) {
            return support.handleAlreadyPaidNotify(context);
        }
    },
    // 父单状态不允许接收支付结果，按策略忽略
    IGNORE_ILLEGAL_PARENT_STATUS {
        @Override
        public String handle(PaymentNotifyContext context, PaymentNotifySupport support) {
            return support.handleIllegalParentStatusNotify(context);
        }
    },
    // 存在子单已关闭等异常分支，避免继续推进正常支付链路
    CLOSED_CHILD {
        @Override
        public String handle(PaymentNotifyContext context, PaymentNotifySupport support) {
            return support.handleClosedChildNotify(context);
        }
    },
    // 标准支付成功路径，推进父子单状态并落后续任务
    NORMAL_PAY {
        @Override
        public String handle(PaymentNotifyContext context, PaymentNotifySupport support) {
            return support.handleNormalPayNotify(context);
        }
    };

    // 每个场景只负责路由，具体编排由 support 中对应方法承接
    public abstract String handle(PaymentNotifyContext context, PaymentNotifySupport support);
}

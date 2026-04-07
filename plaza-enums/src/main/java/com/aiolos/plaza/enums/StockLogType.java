package com.aiolos.plaza.enums;

public enum StockLogType {
    ORDER_DEDUCT(1, "下单扣减"),
    ORDER_ROLLBACK(2, "取消回滚"),
    ADMIN_ADJUST(3, "后台修改"),
    RESERVE_FREEZE(4, "预占冻结"),
    PAY_CONFIRM(5, "支付确认"),
    RESERVE_RELEASE(6, "预占释放"),
    RESERVE_EXPIRE(7, "预占过期"),
    REFUND_ROLLBACK(8, "退款回补");

    private final Integer code;
    private final String desc;

    StockLogType(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public Integer getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}

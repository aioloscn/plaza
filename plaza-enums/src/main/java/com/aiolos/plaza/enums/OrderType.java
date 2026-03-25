package com.aiolos.plaza.enums;

public enum OrderType {
    NORMAL(1, "普通订单"),
    SECKILL(2, "秒杀订单");

    private final Integer code;
    private final String desc;

    OrderType(Integer code, String desc) {
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

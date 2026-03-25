package com.aiolos.plaza.enums;

public enum PayType {
    UNPAID(0, "未支付"),
    ALIPAY(1, "支付宝"),
    WECHAT(2, "微信");

    private final Integer code;
    private final String desc;

    PayType(Integer code, String desc) {
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

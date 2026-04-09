package com.aiolos.plaza.enums;

public enum StockScope {
    NORMAL(1, "普通库存池"),
    SECKILL(2, "秒杀库存池");

    private final Integer code;
    private final String desc;

    StockScope(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public Integer getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static StockScope fromCode(Integer code) {
        if (code == null) {
            return NORMAL;
        }
        for (StockScope scope : values()) {
            if (scope.code.equals(code)) {
                return scope;
            }
        }
        return NORMAL;
    }
}

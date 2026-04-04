package com.aiolos.plaza.enums;

public enum StockReservationState {
    FROZEN(0, "冻结中"),
    CONFIRMED(1, "已确认"),
    RELEASED(2, "已释放"),
    EXPIRED(3, "已过期");

    private final Integer code;
    private final String desc;

    StockReservationState(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public Integer getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static StockReservationState fromCode(Integer code) {
        for (StockReservationState state : values()) {
            if (state.code.equals(code)) {
                return state;
            }
        }
        return null;
    }
}

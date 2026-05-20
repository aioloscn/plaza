package com.aiolos.plaza.enums;

import java.util.Arrays;

public enum ProductBizType {
    LOCAL_RETAIL(1, "外卖/即时零售"),
    ECOMMERCE(2, "电商");

    private final Integer code;
    private final String desc;

    ProductBizType(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public Integer getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static ProductBizType fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(item -> item.code.equals(code))
                .findFirst()
                .orElse(null);
    }
}

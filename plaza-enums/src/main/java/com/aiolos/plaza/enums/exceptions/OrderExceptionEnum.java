package com.aiolos.plaza.enums.exceptions;

import com.aiolos.common.enums.error.CommonError;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum OrderExceptionEnum implements CommonError {
    
    CART_EMPTY(1001, "购物车为空"),
    ;
    
    private final Integer errCode;
    private final String errMsg;

    @Override
    public CommonError setErrMsg(String s) {
        return null;
    }
}

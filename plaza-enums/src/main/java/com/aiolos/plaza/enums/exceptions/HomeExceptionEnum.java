package com.aiolos.plaza.enums.exceptions;

import com.aiolos.common.enums.error.CommonError;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * plaza-home 首页业务异常定义
 */
@Getter
@AllArgsConstructor
public enum HomeExceptionEnum implements CommonError {

    HOME_ES_QUERY_FAIL(3001, "门店检索失败，请稍后重试"),
    HOME_ES_RESPONSE_PARSE_FAIL(3002, "门店检索结果解析失败");

    private final Integer errCode;
    private final String errMsg;

    @Override
    public CommonError setErrMsg(String s) {
        return null;
    }
}

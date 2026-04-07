package com.aiolos.plaza.enums.exceptions;

import com.aiolos.common.enums.error.CommonError;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum OrderExceptionEnum implements CommonError {
    
    CART_EMPTY(1001, "购物车为空"),
    PRODUCT_NOT_EXIST(1002, "商品不存在"),
    STOCK_NOT_ENOUGH(1003, "库存不足"),
    ADDRESS_NOT_EXIST(1004, "收货地址不存在"),
    ORDER_NOT_EXIST(1005, "订单不存在"),
    ORDER_NO_PERMISSION(1006, "无权查看该订单"),
    ORDER_STOCK_RELEASE_FAIL(1007, "订单取消归还库存失败"),
    ORDER_STATUS_ERROR(1008, "订单状态错误"),
    CREATE_PAY_FORM_FAIL(1009, "生成支付表单失败"),
    ORDER_CONFIRM_INVALID(1010, "订单信息已变更，请重新确认后提交"),
    ORDER_REFUND_FAIL(1011, "订单退款失败"),
    ;
    
    private final Integer errCode;
    private final String errMsg;

    @Override
    public CommonError setErrMsg(String s) {
        return null;
    }
}

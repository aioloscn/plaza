package com.aiolos.plaza.enums.exceptions;

import com.aiolos.common.enums.error.CommonError;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ProductExceptionEnum implements CommonError {

    PRODUCT_PARAM_INVALID(1201, "商品参数不合法"),
    PRODUCT_SHOP_ID_REQUIRED(1202, "店铺ID不能为空"),
    PRODUCT_SPU_NAME_REQUIRED(1203, "SPU名称不能为空"),
    PRODUCT_SKU_LIST_EMPTY(1204, "SKU列表不能为空"),
    PRODUCT_SKU_NAME_REQUIRED(1205, "SKU名称不能为空"),
    PRODUCT_SKU_SALE_PRICE_REQUIRED(1206, "SKU销售价不能为空"),
    PRODUCT_SKU_STOCK_INVALID(1207, "SKU库存参数不合法"),
    PRODUCT_PUBLISH_LIST_EMPTY(1208, "发布关系不能为空"),
    PRODUCT_BIZ_TYPE_INVALID(1209, "业务类型不合法"),
    PRODUCT_SALE_ATTR_INVALID(1210, "SKU销售属性不合法"),
    PRODUCT_NOT_EXIST(1211, "商品不存在"),
    PRODUCT_SPU_ID_REQUIRED(1212, "SPU ID不能为空"),
    PRODUCT_PUBLISH_ID_REQUIRED(1213, "发布关系ID不能为空"),
    PRODUCT_PUBLISH_NOT_EXIST(1214, "发布关系不存在"),
    PRODUCT_SKU_NOT_EXIST(1215, "SKU不存在"),
    PRODUCT_LAST_SKU_DELETE_FORBIDDEN(1216, "最后一个SKU不能单独删除，请直接删除商品");

    private final Integer errCode;
    private final String errMsg;

    @Override
    public CommonError setErrMsg(String s) {
        return null;
    }
}

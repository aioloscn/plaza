package com.aiolos.plaza.product.service.facade;

import com.aiolos.plaza.enums.ProductBizType;
import com.aiolos.plaza.product.model.dto.ProductStorefrontSkuDTO;
import java.util.List;

/**
 * 面向店铺前台的商品读取门面
 * 主要给 shop 模块的公开商品列表/详情接口复用，避免继续直接读旧 product 表
 */
public interface ProductStorefrontFacade {

    List<ProductStorefrontSkuDTO> listShopSkuSnapshots(Long shopId, ProductBizType bizType);

    ProductStorefrontSkuDTO getShopSkuSnapshot(Long skuId, ProductBizType bizType);
}

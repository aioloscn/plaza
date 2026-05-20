package com.aiolos.plaza.product.service.app;

import com.aiolos.plaza.product.model.bo.ProductManagePageReq;
import com.aiolos.plaza.product.model.bo.ProductPublishUpdateReq;
import com.aiolos.plaza.product.model.bo.ProductSaveReq;
import com.aiolos.plaza.product.model.dto.ProductManageDetailDTO;
import com.aiolos.plaza.product.model.dto.ProductManagePageDTO;
import com.aiolos.plaza.product.model.dto.ProductSaveResultDTO;

public interface ProductWriteAppService {

    /**
     * 新建统一商品
     */
    ProductSaveResultDTO createProduct(ProductSaveReq req);

    /**
     * 查询统一商品详情
     */
    ProductManageDetailDTO getProductDetail(Long spuId);

    /**
     * 分页查询统一商品列表
     */
    ProductManagePageDTO pageProducts(ProductManagePageReq req);

    /**
     * 更新统一商品
     */
    ProductSaveResultDTO updateProduct(ProductSaveReq req);

    /**
     * 单独更新发布关系
     */
    void updatePublish(ProductPublishUpdateReq req);

    /**
     * 删除统一商品
     */
    void deleteProduct(Long spuId);

    /**
     * 删除单个SKU
     */
    void deleteSku(Long skuId);
}

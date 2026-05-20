package com.aiolos.plaza.shop.service;

import com.aiolos.plaza.model.po.Product;
import com.aiolos.plaza.shop.model.vo.ProductVO;

import java.util.List;

/**
 * 店铺商品服务接口
 */
public interface ShopProductService {

    /**
     * 根据店铺ID查询商品列表
     * @param shopId
     * @return
     */
    List<ProductVO> listByShopId(Long shopId);

    /**
     * 根据店铺ID查询前台统一商品列表
     * 当前返回的 `id` 语义已切换为真实 `skuId`
     */
    List<ProductVO> listStorefrontByShopId(Long shopId);
    
    /**
     * 根据ID查询商品详情
     * @param id
     * @return
     */
    ProductVO getById(Long id);

    /**
     * 根据 skuId 查询前台统一商品详情
     */
    ProductVO getStorefrontBySkuId(Long skuId);

    /**
     * 更新商品信息（包含缓存双删逻辑）
     * @param product
     * @return
     */
    boolean updateProduct(Product product);

    /**
     * 清理本地商品缓存
     * @param id 商品ID
     */
    void clearLocalCache(Long id);
}

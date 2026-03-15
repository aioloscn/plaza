package com.aiolos.plaza.shop.service.impl;

import com.aiolos.common.enums.base.BoolEnum;
import com.aiolos.common.util.ConvertBeanUtil;
import com.aiolos.plaza.model.po.Product;
import com.aiolos.plaza.service.ProductService;
import com.aiolos.plaza.shop.model.vo.ProductVO;
import com.aiolos.plaza.shop.service.ShopProductService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 店铺商品服务实现类
 */
@Service
public class ShopProductServiceImpl implements ShopProductService {

    @Autowired
    private ProductService productService;

    @Override
    public List<ProductVO> listByShopId(Long shopId) {
        return ConvertBeanUtil.convertList(productService.list(new QueryWrapper<Product>()
                .eq(Product.SHOP_ID, shopId)
                .eq(Product.STATUS, BoolEnum.YES.getValue())), ProductVO.class);
    }

    @Override
    public ProductVO getById(Long id) {
        return ConvertBeanUtil.convert(productService.getById(id), ProductVO.class);
    }
}

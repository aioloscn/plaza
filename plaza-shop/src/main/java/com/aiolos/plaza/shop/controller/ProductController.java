package com.aiolos.plaza.shop.controller;

import com.aiolos.common.cloud.annotation.IgnoreAuth;
import com.aiolos.plaza.shop.model.vo.ProductVO;
import com.aiolos.plaza.shop.service.ShopProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/product")
@Tag(name = "商品服务")
@IgnoreAuth
public class ProductController {

    @Autowired
    private ShopProductService shopProductService;

    
    @GetMapping("/list")
    @Operation(summary = "根据店铺ID查询商品列表")
    public List<ProductVO> list(@RequestParam("shopId") Long shopId) {
        return shopProductService.listByShopId(shopId);
    }

    @GetMapping("/{id}")
    @Operation(summary = "根据ID查询商品详情")
    public ProductVO detail(@PathVariable("id") Long id) {
        return shopProductService.getById(id);
    }
}

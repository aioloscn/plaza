package com.aiolos.plaza.shop.controller;

import com.aiolos.common.cloud.annotation.IgnoreAuth;
import com.aiolos.plaza.product.model.bo.ProductManagePageReq;
import com.aiolos.plaza.product.model.bo.ProductPublishUpdateReq;
import com.aiolos.plaza.product.model.bo.ProductSaveReq;
import com.aiolos.plaza.product.model.dto.ProductManageDetailDTO;
import com.aiolos.plaza.product.model.dto.ProductManagePageDTO;
import com.aiolos.plaza.product.model.dto.ProductSaveResultDTO;
import com.aiolos.plaza.product.service.app.ProductWriteAppService;
import com.aiolos.plaza.shop.service.ProductCenterCacheService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/product/manage")
@Tag(name = "统一商品后台接口")
@IgnoreAuth
@RequiredArgsConstructor
public class ProductManageController {

    private final ProductWriteAppService productWriteAppService;
    private final ProductCenterCacheService productCenterCacheService;

    @PostMapping("/create")
    @Operation(summary = "新建统一商品", description = "一次写入 SPU、SKU、发布关系以及场景扩展配置")
    public ProductSaveResultDTO create(@RequestBody ProductSaveReq req) {
        ProductSaveResultDTO result = productWriteAppService.createProduct(req);
        productCenterCacheService.evictLocalRetailSkuSnapshots(result.getSkuIds());
        return result;
    }

    @PostMapping("/page")
    @Operation(summary = "分页查询统一商品", description = "按店铺、状态、关键词和业务线分页查询商品")
    public ProductManagePageDTO page(@RequestBody ProductManagePageReq req) {
        return productWriteAppService.pageProducts(req);
    }

    @GetMapping("/{spuId}")
    @Operation(summary = "查询统一商品详情", description = "返回后台经营页需要的 SPU、SKU 与发布关系完整结构")
    public ProductManageDetailDTO detail(@PathVariable("spuId") Long spuId) {
        return productWriteAppService.getProductDetail(spuId);
    }

    @PostMapping("/update")
    @Operation(summary = "更新统一商品", description = "按请求快照全量覆盖商品从属结构")
    public ProductSaveResultDTO update(@RequestBody ProductSaveReq req) {
        productCenterCacheService.evictBySpuId(req.getSpuId());
        ProductSaveResultDTO result = productWriteAppService.updateProduct(req);
        productCenterCacheService.evictLocalRetailSkuSnapshots(result.getSkuIds());
        return result;
    }

    @PostMapping("/publish/update")
    @Operation(summary = "更新商品发布关系", description = "单独修改发布关系的改价、上下架、显隐与排序")
    public void updatePublish(@RequestBody ProductPublishUpdateReq req) {
        productWriteAppService.updatePublish(req);
        productCenterCacheService.evictByPublishId(req.getPublishId());
    }

    @DeleteMapping("/{spuId}")
    @Operation(summary = "删除统一商品", description = "删除 SPU 及其全部 SKU、发布关系和扩展数据")
    public void delete(@PathVariable("spuId") Long spuId) {
        productCenterCacheService.evictBySpuId(spuId);
        productWriteAppService.deleteProduct(spuId);
    }

    @DeleteMapping("/sku/{skuId}")
    @Operation(summary = "删除单个SKU", description = "删除一个 SKU 及其从属数据，最后一个 SKU 不允许单独删除")
    public void deleteSku(@PathVariable("skuId") Long skuId) {
        productCenterCacheService.evictBySkuId(skuId);
        productWriteAppService.deleteSku(skuId);
    }
}

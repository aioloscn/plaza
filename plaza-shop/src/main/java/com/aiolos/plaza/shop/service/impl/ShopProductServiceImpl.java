package com.aiolos.plaza.shop.service.impl;

import com.aiolos.common.enums.base.BoolEnum;
import com.aiolos.common.util.ConvertBeanUtil;
import com.aiolos.plaza.enums.ProductBizType;
import com.aiolos.plaza.enums.RedisKeyEnum;
import com.aiolos.plaza.mapper.ProductStockLogMapper;
import com.aiolos.plaza.model.po.Product;
import com.aiolos.plaza.model.po.ProductStockLog;
import com.aiolos.plaza.product.model.dto.ProductStorefrontSkuDTO;
import com.aiolos.plaza.product.service.facade.ProductStorefrontFacade;
import com.aiolos.plaza.service.ProductService;
import com.aiolos.plaza.service.SeckillActivityService;
import com.aiolos.plaza.shop.model.vo.ProductVO;
import com.aiolos.plaza.shop.mq.producer.ProductMessageProducer;
import com.aiolos.plaza.shop.service.ShopProductService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 店铺商品服务实现类
 */
@Slf4j
@Service
@AllArgsConstructor
public class ShopProductServiceImpl implements ShopProductService {

    private final ProductService productService;
    
    private final SeckillActivityService seckillActivityService;

    private final ProductStockLogMapper productStockLogMapper;

    private final StringRedisTemplate stringRedisTemplate;

    private final ProductMessageProducer productMessageProducer;
    
    private final ObjectMapper objectMapper;

    private final ProductStorefrontFacade productStorefrontFacade;

    // 本地缓存 Caffeine (L1 缓存)
    private final Cache<Long, ProductVO> localProductCache = Caffeine.newBuilder()
            .initialCapacity(100)
            .maximumSize(2000)
            .expireAfterWrite(10, TimeUnit.MINUTES) // 10分钟过期，配合Redis(L2)和MQ清理使用
            .build();

    @Override
    public List<ProductVO> listByShopId(Long shopId) {
        List<Product> productList = productService.list(new QueryWrapper<Product>()
                .eq(Product.SHOP_ID, shopId)
                .eq(Product.STATUS, BoolEnum.YES.getValue()));
                
        // 异步或同步将商品信息写入 Redis
        for (Product product : productList) {
            try {
                // 缓存商品信息，过期时间1天
                stringRedisTemplate.opsForValue().set(RedisKeyEnum.PRODUCT_INFO.getKey(product.getId()), 
                        objectMapper.writeValueAsString(product), 1, TimeUnit.DAYS);
                // 初始化库存缓存，如果不存在才设置
                stringRedisTemplate.opsForValue().setIfAbsent(RedisKeyEnum.PRODUCT_STOCK.getKey(product.getId()), 
                        String.valueOf(product.getStock()));
            } catch (JsonProcessingException e) {
                log.error("缓存商品信息失败，商品ID: {}", product.getId(), e);
            }
        }
        
        List<ProductVO> voList = ConvertBeanUtil.convertList(productList, ProductVO.class);
        
        // 顺便写入本地缓存
        if (voList != null) {
            for (ProductVO vo : voList) {
                localProductCache.put(vo.getId(), vo);
            }
        }
        
        return voList;
    }

    @Override
    public List<ProductVO> listStorefrontByShopId(Long shopId) {
        // 对外商品列表已切到统一商品中心，当前只读取本地零售发布数据
        List<ProductStorefrontSkuDTO> skuSnapshotList = productStorefrontFacade.listShopSkuSnapshots(shopId, ProductBizType.LOCAL_RETAIL);
        if (skuSnapshotList == null || skuSnapshotList.isEmpty()) {
            return List.of();
        }
        return skuSnapshotList.stream().map(this::toStorefrontProductVO).toList();
    }

    @Override
    public ProductVO getById(Long id) {
        // 0. 先查本地缓存 Caffeine (L1)
        ProductVO localProduct = localProductCache.getIfPresent(id);
        if (localProduct != null) {
            return localProduct;
        }

        // 1. 本地缓存未命中，查 Redis (L2)
        String productJson = stringRedisTemplate.opsForValue().get(RedisKeyEnum.PRODUCT_INFO.getKey(id));
        if (productJson != null) {
            try {
                Product product = objectMapper.readValue(productJson, Product.class);
                ProductVO productVO = ConvertBeanUtil.convert(product, ProductVO.class);
                // 回写本地缓存
                localProductCache.put(id, productVO);
                return productVO;
            } catch (JsonProcessingException e) {
                log.error("反序列化商品信息失败，商品ID: {}", id, e);
                // 如果反序列化失败，则继续查数据库
            }
        }

        // 2. Redis 没有，查数据库
        Product product = productService.getById(id);
        if (product != null && product.getStatus().equals(BoolEnum.YES.getValue())) {
            try {
                // 3. 查到后回写 Redis
                // 缓存商品信息，过期时间1天
                stringRedisTemplate.opsForValue().set(RedisKeyEnum.PRODUCT_INFO.getKey(product.getId()), 
                        objectMapper.writeValueAsString(product), 1, TimeUnit.DAYS);
                // 初始化库存缓存
                stringRedisTemplate.opsForValue().setIfAbsent(RedisKeyEnum.PRODUCT_STOCK.getKey(product.getId()), 
                        String.valueOf(product.getStock()));
                        
                // 回写本地缓存
                ProductVO productVO = ConvertBeanUtil.convert(product, ProductVO.class);
                localProductCache.put(id, productVO);
                return productVO;
            } catch (JsonProcessingException e) {
                log.error("缓存商品信息失败，商品ID: {}", product.getId(), e);
            }
        }
        return ConvertBeanUtil.convert(product, ProductVO.class);
    }

    @Override
    public ProductVO getStorefrontBySkuId(Long skuId) {
        ProductStorefrontSkuDTO skuSnapshot = productStorefrontFacade.getShopSkuSnapshot(skuId, ProductBizType.LOCAL_RETAIL);
        return toStorefrontProductVO(skuSnapshot);
    }

    @Override
    public boolean updateProduct(Product product) {
        if (product == null || product.getId() == null) {
            return false;
        }
        
        Long productId = product.getId();
        
        // 查询旧商品信息以比对库存是否发生变化
        Product oldProduct = productService.getById(productId);
        int stockDiff = 0;
        if (oldProduct != null && product.getStock() != null && !product.getStock().equals(oldProduct.getStock())) {
            stockDiff = product.getStock() - oldProduct.getStock();
        }

        // 0. 第一次清理本地缓存 L1
        localProductCache.invalidate(productId);
        
        // 1. 第一次删除缓存 L2
        try {
            stringRedisTemplate.delete(RedisKeyEnum.PRODUCT_INFO.getKey(productId));
            stringRedisTemplate.delete(RedisKeyEnum.PRODUCT_STOCK.getKey(productId));
            log.info("商品更新：第一次删除缓存成功，商品ID: {}", productId);
        } catch (Exception e) {
            log.error("商品更新：第一次删除缓存失败，商品ID: {}", productId, e);
        }
        
        // 2. 更新数据库
        boolean updateResult = productService.updateById(product);
        
        if (updateResult && stockDiff != 0) {
            // 记录库存操作日志（后台修改）
            ProductStockLog stockLog = new ProductStockLog();
            // 当前本地零售单规格商品过渡期内，旧 productId 直接作为 skuId 记入库存日志
            stockLog.setSkuId(productId);
            stockLog.setAmount(stockDiff);
            stockLog.setType(3); // 3-后台修改
            stockLog.setCreateTime(LocalDateTime.now());
            productStockLogMapper.insert(stockLog);
        }
        
        // 3. 发送延迟消息进行第二次删除缓存（延迟级别 1，大约1秒）
        if (updateResult) {
            productMessageProducer.sendCacheDeleteDelayMessage(productId, 1);
        }
        
        return updateResult;
    }

    @Override
    public void clearLocalCache(Long id) {
        if (id != null) {
            localProductCache.invalidate(id);
            log.info("本地商品缓存 L1 清理成功，商品ID: {}", id);
        }
    }

    private ProductVO toStorefrontProductVO(ProductStorefrontSkuDTO skuSnapshot) {
        if (skuSnapshot == null || skuSnapshot.getSkuId() == null) {
            return null;
        }
        ProductVO productVO = new ProductVO();
        // 旧前台接口字段名仍叫 id，这里返回真实 skuId，避免继续透出旧 product.id
        productVO.setId(skuSnapshot.getSkuId());
        productVO.setShopId(skuSnapshot.getShopId());
        productVO.setName(skuSnapshot.getName());
        productVO.setPrice(skuSnapshot.getPrice());
        productVO.setStock(skuSnapshot.getStock());
        productVO.setDescription(skuSnapshot.getDescription());
        productVO.setImageUrl(skuSnapshot.getImageUrl());
        productVO.setStatus(skuSnapshot.getStatus());
        return productVO;
    }
}

package com.aiolos.plaza.shop.service.impl;

import com.aiolos.common.enums.base.BoolEnum;
import com.aiolos.common.util.ConvertBeanUtil;
import com.aiolos.plaza.model.po.Product;
import com.aiolos.plaza.service.ProductService;
import com.aiolos.plaza.shop.model.vo.ProductVO;
import com.aiolos.plaza.shop.mq.producer.ProductMessageProducer;
import com.aiolos.plaza.shop.service.ShopProductService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 店铺商品服务实现类
 */
@Slf4j
@Service
public class ShopProductServiceImpl implements ShopProductService {

    @Autowired
    private ProductService productService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ProductMessageProducer productMessageProducer;

    private static final String PRODUCT_INFO_PREFIX = "product:info:";
    private static final String PRODUCT_STOCK_PREFIX = "product:stock:";
    
    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public List<ProductVO> listByShopId(Long shopId) {
        List<Product> productList = productService.list(new QueryWrapper<Product>()
                .eq(Product.SHOP_ID, shopId)
                .eq(Product.STATUS, BoolEnum.YES.getValue()));
                
        // 异步或同步将商品信息写入 Redis
        for (Product product : productList) {
            try {
                // 缓存商品信息，过期时间1天
                stringRedisTemplate.opsForValue().set(PRODUCT_INFO_PREFIX + product.getId(), 
                        objectMapper.writeValueAsString(product), 1, TimeUnit.DAYS);
                // 初始化库存缓存，如果不存在才设置
                stringRedisTemplate.opsForValue().setIfAbsent(PRODUCT_STOCK_PREFIX + product.getId(), 
                        String.valueOf(product.getStock()));
            } catch (JsonProcessingException e) {
                log.error("缓存商品信息失败，商品ID: {}", product.getId(), e);
            }
        }
        
        return ConvertBeanUtil.convertList(productList, ProductVO.class);
    }

    @Override
    public ProductVO getById(Long id) {
        // 1. 先查 Redis
        String productJson = stringRedisTemplate.opsForValue().get(PRODUCT_INFO_PREFIX + id);
        if (productJson != null) {
            try {
                Product product = objectMapper.readValue(productJson, Product.class);
                return ConvertBeanUtil.convert(product, ProductVO.class);
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
                stringRedisTemplate.opsForValue().set(PRODUCT_INFO_PREFIX + product.getId(), 
                        objectMapper.writeValueAsString(product), 1, TimeUnit.DAYS);
                // 初始化库存缓存
                stringRedisTemplate.opsForValue().setIfAbsent(PRODUCT_STOCK_PREFIX + product.getId(), 
                        String.valueOf(product.getStock()));
            } catch (JsonProcessingException e) {
                log.error("缓存商品信息失败，商品ID: {}", product.getId(), e);
            }
        }
        return ConvertBeanUtil.convert(product, ProductVO.class);
    }

    @Override
    public boolean updateProduct(Product product) {
        if (product == null || product.getId() == null) {
            return false;
        }
        
        Long productId = product.getId();
        
        // 1. 第一次删除缓存
        try {
            stringRedisTemplate.delete(PRODUCT_INFO_PREFIX + productId);
            stringRedisTemplate.delete(PRODUCT_STOCK_PREFIX + productId);
            log.info("商品更新：第一次删除缓存成功，商品ID: {}", productId);
        } catch (Exception e) {
            log.error("商品更新：第一次删除缓存失败，商品ID: {}", productId, e);
        }
        
        // 2. 更新数据库
        boolean updateResult = productService.updateById(product);
        
        // 3. 发送延迟消息进行第二次删除缓存（延迟级别 1，大约1秒）
        if (updateResult) {
            productMessageProducer.sendCacheDeleteDelayMessage(productId, 1);
        }
        
        return updateResult;
    }
}

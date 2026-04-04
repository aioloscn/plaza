package com.aiolos.plaza.mapper;

import com.aiolos.plaza.model.po.ProductStockAggregate;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface ProductStockAggregateMapper extends BaseMapper<ProductStockAggregate> {

    @Insert("INSERT INTO product_stock_aggregate(product_id, available_stock, frozen_stock, confirmed_stock, version, create_time, update_time) " +
            "VALUES(#{productId}, #{availableStock}, 0, 0, 0, #{now}, #{now}) " +
            "ON DUPLICATE KEY UPDATE update_time = VALUES(update_time)")
    int initAggregate(@Param("productId") Long productId, @Param("availableStock") Integer availableStock, @Param("now") LocalDateTime now);
}

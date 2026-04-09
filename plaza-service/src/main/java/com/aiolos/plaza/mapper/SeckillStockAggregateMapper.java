package com.aiolos.plaza.mapper;

import com.aiolos.plaza.model.po.SeckillStockAggregate;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface SeckillStockAggregateMapper extends BaseMapper<SeckillStockAggregate> {

    @Insert("INSERT INTO seckill_stock_aggregate(activity_id, product_id, available_stock, frozen_stock, confirmed_stock, version, create_time, update_time) " +
            "VALUES(#{activityId}, #{productId}, 0, 0, 0, 0, #{now}, #{now}) " +
            "ON DUPLICATE KEY UPDATE update_time = VALUES(update_time)")
    int initAggregate(@Param("activityId") Long activityId,
                      @Param("productId") Long productId,
                      @Param("now") LocalDateTime now);
}

package com.aiolos.plaza.mapper;

import com.aiolos.plaza.model.po.Order;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 订单表 Mapper 接口
 * </p>
 *
 * @author aiolos
 * @since 2026-03-16
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {

}

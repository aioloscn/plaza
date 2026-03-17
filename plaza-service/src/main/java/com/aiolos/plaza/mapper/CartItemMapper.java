package com.aiolos.plaza.mapper;

import com.aiolos.plaza.model.po.CartItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 购物车项 Mapper 接口
 */
@Mapper
public interface CartItemMapper extends BaseMapper<CartItem> {
}

package com.aiolos.plaza.mapper;

import com.aiolos.plaza.model.po.Product;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 商品表 Mapper 接口
 * </p>
 *
 * @author Aiolos
 * @since 2025-08-22
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

}

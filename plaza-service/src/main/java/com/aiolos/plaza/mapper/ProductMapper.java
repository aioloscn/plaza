package com.aiolos.plaza.mapper;

import com.aiolos.plaza.model.po.Product;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

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

    /**
     * 扣减库存，使用乐观锁保证库存不超卖
     *
     * @param id 商品ID
     * @param quantity 扣减数量
     * @return 影响行数
     */
    @Update("UPDATE product SET stock = stock - #{quantity} WHERE id = #{id} AND stock >= #{quantity}")
    int deductStock(@Param("id") Long id, @Param("quantity") Integer quantity);

    /**
     * 恢复库存
     *
     * @param id 商品ID
     * @param quantity 恢复数量
     * @return 影响行数
     */
    @Update("UPDATE product SET stock = stock + #{quantity} WHERE id = #{id}")
    int addStock(@Param("id") Long id, @Param("quantity") Integer quantity);

}

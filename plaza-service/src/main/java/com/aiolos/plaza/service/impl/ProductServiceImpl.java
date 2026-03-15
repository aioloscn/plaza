package com.aiolos.plaza.service.impl;

import com.aiolos.plaza.mapper.ProductMapper;
import com.aiolos.plaza.model.po.Product;
import com.aiolos.plaza.service.ProductService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 商品表 服务实现类
 * </p>
 *
 * @author Aiolos
 * @since 2025-08-22
 */
@Service
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product> implements ProductService {

}

package com.aiolos.plaza.service.impl;

import com.aiolos.plaza.model.po.Category;
import com.aiolos.plaza.mapper.CategoryMapper;
import com.aiolos.plaza.service.CategoryService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 店铺品类表 服务实现类
 * </p>
 *
 * @author Aiolos
 * @since 2025-08-22
 */
@Service
public class CategoryServiceImpl extends ServiceImpl<CategoryMapper, Category> implements CategoryService {

}

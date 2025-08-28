package com.aiolos.plaza.home.service.impl;

import com.aiolos.common.util.ConvertBeanUtil;
import com.aiolos.plaza.home.model.vo.CategoryVO;
import com.aiolos.plaza.home.service.HomeCategoryService;
import com.aiolos.plaza.model.po.Category;
import com.aiolos.plaza.service.CategoryService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@AllArgsConstructor
public class HomeCategoryServiceImpl implements HomeCategoryService {

    private final CategoryService categoryService;
    
    @Override
    public List<CategoryVO> list() {
        List<Category> list = categoryService.list();
        return ConvertBeanUtil.convertList(list, CategoryVO.class);
    }
}

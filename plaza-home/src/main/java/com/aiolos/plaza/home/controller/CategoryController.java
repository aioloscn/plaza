package com.aiolos.plaza.home.controller;

import com.aiolos.plaza.home.model.vo.CategoryVO;
import com.aiolos.plaza.home.service.HomeCategoryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/category")
@Tag(name = "品类服务")
public class CategoryController {
    
    @Autowired
    private HomeCategoryService homeCategoryService;
    
    @GetMapping("/list")
    public List<CategoryVO> list() {
        return homeCategoryService.list();
    }
}

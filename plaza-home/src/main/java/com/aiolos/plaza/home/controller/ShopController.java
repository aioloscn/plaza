package com.aiolos.plaza.home.controller;

import com.aiolos.common.cloud.annotation.IgnoreAuth;
import com.aiolos.common.util.PageConvertUtil;
import com.aiolos.common.wrapper.PageModel;
import com.aiolos.common.wrapper.PageResult;
import com.aiolos.plaza.home.model.bo.RecommendShopBO;
import com.aiolos.plaza.home.model.bo.SearchShopBO;
import com.aiolos.plaza.home.model.vo.RecommendShopVO;
import com.aiolos.plaza.home.service.HomeShopService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/shop")
@Tag(name = "门店服务")
@IgnoreAuth
public class ShopController {
    
    @Autowired
    private HomeShopService homeShopService;
    
    @PostMapping("/recommend")
    public PageResult<RecommendShopVO> recommend(@RequestBody PageModel<RecommendShopBO> model) {
        if (model.getData() == null || model.getData().getLongitude() == null || model.getData().getLatitude() == null)
            return PageConvertUtil.convert(model.getPage(RecommendShopVO.class));
        
        return homeShopService.recommend(model);
    }
    
    @PostMapping("/search")
    public PageResult<RecommendShopVO> search(@RequestBody PageModel<SearchShopBO> model) {
        if (model.getData() == null || model.getData().getLongitude() == null || model.getData().getLatitude() == null || model.getData().getKeyword() == null)
            return PageConvertUtil.convert(model.getPage(RecommendShopVO.class));
        
        return homeShopService.search(model);
    }
    
    @PostMapping("/searchES")
    public PageResult<RecommendShopVO> searchES(@RequestBody PageModel<SearchShopBO> model) {
        if (model.getData() == null || model.getData().getLongitude() == null || model.getData().getLatitude() == null || model.getData().getKeyword() == null)
            return PageConvertUtil.convert(model.getPage(RecommendShopVO.class));
        
        return homeShopService.searchES(model);
    }
    
    @GetMapping("/{id}")
    @Operation(summary = "根据ID查询店铺详情")
    public RecommendShopVO detail(@PathVariable("id") Long id) {
        return homeShopService.detail(id);
    }

}

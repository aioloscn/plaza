package com.aiolos.plaza.home.controller;

import com.aiolos.common.util.PageConvertUtil;
import com.aiolos.common.wrapper.PageModel;
import com.aiolos.common.wrapper.PageResult;
import com.aiolos.plaza.home.model.bo.RecommendShopBO;
import com.aiolos.plaza.home.model.bo.SearchShopBO;
import com.aiolos.plaza.home.model.vo.RecommendShopVO;
import com.aiolos.plaza.home.service.HomeShopService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shop")
@Tag(name = "门店服务")
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
}

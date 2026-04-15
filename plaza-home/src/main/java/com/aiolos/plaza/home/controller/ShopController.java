package com.aiolos.plaza.home.controller;

import com.aiolos.common.cloud.annotation.IgnoreAuth;
import com.aiolos.common.util.PageConvertUtil;
import com.aiolos.common.wrapper.PageModel;
import com.aiolos.common.wrapper.PageResult;
import com.aiolos.plaza.home.canal.CanalScheduling;
import com.aiolos.plaza.home.model.bo.RecommendShopBO;
import com.aiolos.plaza.home.model.bo.SearchShopBO;
import com.aiolos.plaza.home.model.bo.UserProfileSearchShopBO;
import com.aiolos.plaza.home.model.vo.RecommendShopVO;
import com.aiolos.plaza.home.service.HomeShopService;
import com.aiolos.plaza.home.service.UserProfileShopSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/shop")
@Tag(name = "门店服务")
@IgnoreAuth
public class ShopController {
    
    @Autowired
    private HomeShopService homeShopService;

    @Autowired
    private UserProfileShopSearchService userProfileShopSearchService;

    @Autowired
    private CanalScheduling canalScheduling;
    
    @PostMapping("/recommend")
    public PageResult<RecommendShopVO> recommend(@RequestBody PageModel<RecommendShopBO> model) {
        if (model.getData() == null || model.getData().getLongitude() == null || model.getData().getLatitude() == null)
            return PageConvertUtil.convert(model.getPage(RecommendShopVO.class));
        
        // 统一入口，内部根据灰度结果决定走用户画像ES或旧版推荐
        return userProfileShopSearchService.recommendES(model);
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

        // 统一入口，内部根据灰度结果决定走用户画像ES或旧版ES
        return userProfileShopSearchService.searchES(convertToUserProfileModel(model));
    }
    
    @GetMapping("/{id}")
    @Operation(summary = "根据ID查询店铺详情")
    public RecommendShopVO detail(@PathVariable("id") Long id) {
        return homeShopService.detail(id);
    }

    @PostMapping("/sync/full")
    @Operation(summary = "手动触发店铺ES全量同步")
    @IgnoreAuth
    public Map<String, Object> fullSync(@RequestParam(value = "batchSize", required = false) Integer batchSize) {
        return canalScheduling.manualFullSync(batchSize);
    }

    private PageModel<UserProfileSearchShopBO> convertToUserProfileModel(PageModel<SearchShopBO> model) {
        UserProfileSearchShopBO req = new UserProfileSearchShopBO();
        req.setLongitude(model.getData().getLongitude());
        req.setLatitude(model.getData().getLatitude());
        req.setKeyword(model.getData().getKeyword());
        req.setCategoryId(model.getData().getCategoryId());
        req.setTag(model.getData().getTag());
        req.setOrderBy(model.getData().getOrderBy());
        req.setProfileEnabled(true);

        PageModel<UserProfileSearchShopBO> target = new PageModel<>();
        target.setCurrent(model.getCurrent());
        target.setSize(model.getSize());
        target.setData(req);
        return target;
    }

}

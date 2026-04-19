package com.aiolos.plaza.home.service;

import com.aiolos.common.wrapper.PageModel;
import com.aiolos.common.wrapper.PageResult;
import com.aiolos.plaza.home.model.bo.RecommendShopBO;
import com.aiolos.plaza.home.model.bo.UserProfileSearchShopBO;
import com.aiolos.plaza.home.model.vo.RecommendShopVO;

/**
 * 用户画像门店搜索服务
 */
public interface UserProfileShopSearchService {

    /**
     * 首页推荐统一入口
     * 命中灰度走新版画像ES，未命中回退旧版推荐
     */
    PageResult<RecommendShopVO> recommendES(PageModel<RecommendShopBO> model);

    /**
     * 基于用户画像做门店ES搜索
     */
    PageResult<RecommendShopVO> searchES(PageModel<UserProfileSearchShopBO> model);
}

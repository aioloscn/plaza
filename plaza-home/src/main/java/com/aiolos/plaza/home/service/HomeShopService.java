package com.aiolos.plaza.home.service;

import com.aiolos.common.wrapper.PageModel;
import com.aiolos.common.wrapper.PageResult;
import com.aiolos.plaza.home.model.bo.RecommendShopBO;
import com.aiolos.plaza.home.model.bo.SearchShopBO;
import com.aiolos.plaza.home.model.vo.RecommendShopVO;

public interface HomeShopService {

    /**
     * 推荐门店
     * @param model
     * @return
     */
    PageResult<RecommendShopVO> recommend(PageModel<RecommendShopBO> model);

    PageResult<RecommendShopVO> search(PageModel<SearchShopBO> model);
}

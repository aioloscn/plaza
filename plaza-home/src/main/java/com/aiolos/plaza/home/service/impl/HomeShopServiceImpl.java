package com.aiolos.plaza.home.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import com.aiolos.common.enums.base.BoolEnum;
import com.aiolos.common.util.ConvertBeanUtil;
import com.aiolos.common.wrapper.PageModel;
import com.aiolos.common.wrapper.PageResult;
import com.aiolos.plaza.home.model.bo.RecommendShopBO;
import com.aiolos.plaza.home.model.bo.SearchShopBO;
import com.aiolos.plaza.home.model.vo.RecommendShopVO;
import com.aiolos.plaza.home.service.HomeShopService;
import com.aiolos.plaza.mapper.ShopMapper;
import com.aiolos.plaza.model.po.Shop;
import com.aiolos.plaza.service.ShopService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class HomeShopServiceImpl implements HomeShopService {
    
    private final ShopService shopService;
    private final ShopMapper shopMapper;

    @Override
    public PageResult<RecommendShopVO> recommend(PageModel<RecommendShopBO> model) {

        RecommendShopBO data = model.getData();
        QueryWrapper<Shop> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("status", BoolEnum.YES.getCode());
        long total = shopService.count(queryWrapper);
        List<Shop> list = shopMapper.recommend(data.getLatitude().doubleValue(), data.getLongitude().doubleValue());
        PageResult<RecommendShopVO> pageResult = new PageResult<>();
        pageResult.setCurrent(model.getCurrent());
        pageResult.setSize(model.getSize());
        pageResult.setTotal(total);
        
        if (CollectionUtil.isNotEmpty(list)) {
            List<RecommendShopVO> records = list.stream().map(shop -> {
                RecommendShopVO vo = ConvertBeanUtil.convert(shop, RecommendShopVO.class);
                if (shop.getDistance() != null && shop.getDistance() > 1000) {
                    vo.setDistance(shop.getDistance() / 1000 + "km");
                } else {
                    vo.setDistance(shop.getDistance() != null ? shop.getDistance() + "m" : "");
                }
                return vo;
            }).collect(Collectors.toList());
            pageResult.setRecords(records);
        }
        return pageResult;
    }

    @Override
    public PageResult<RecommendShopVO> search(PageModel<SearchShopBO> model) {
        
        SearchShopBO data = model.getData();
        QueryWrapper<Shop> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("status", BoolEnum.YES.getCode());
        if (data.getCategoryId() != null) {
            queryWrapper.eq("category_id", data.getCategoryId());
        }
        queryWrapper.and(wrapper -> wrapper
                .like("name", data.getKeyword())
                .or().like("tags", data.getKeyword())
                .or().like("address", data.getKeyword()));
        long total = shopService.count(queryWrapper);
        
        List<Shop> list = shopMapper.search(data.getLatitude().doubleValue(), data.getLongitude().doubleValue(), data.getKeyword(), data.getCategoryId(), data.getOrderBy());
        PageResult<RecommendShopVO> pageResult = new PageResult<>();
        pageResult.setCurrent(model.getCurrent());
        pageResult.setSize(model.getSize());
        pageResult.setTotal(total);
        
        if (CollectionUtil.isNotEmpty(list)) {
            List<RecommendShopVO> records = list.stream().map(shop -> {
                RecommendShopVO vo = ConvertBeanUtil.convert(shop, RecommendShopVO.class);
                if (shop.getDistance() != null && shop.getDistance() > 1000) {
                    vo.setDistance(shop.getDistance() / 1000 + "km");
                } else {
                    vo.setDistance(shop.getDistance() != null ? shop.getDistance() + "m" : "");
                }
                return vo;
            }).collect(Collectors.toList());
            pageResult.setRecords(records);
        }
        return pageResult;
    }
}

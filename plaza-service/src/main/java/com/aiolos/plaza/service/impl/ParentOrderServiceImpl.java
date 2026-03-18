package com.aiolos.plaza.service.impl;

import com.aiolos.plaza.mapper.ParentOrderMapper;
import com.aiolos.plaza.model.po.ParentOrder;
import com.aiolos.plaza.service.ParentOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class ParentOrderServiceImpl extends ServiceImpl<ParentOrderMapper, ParentOrder> implements ParentOrderService {
}

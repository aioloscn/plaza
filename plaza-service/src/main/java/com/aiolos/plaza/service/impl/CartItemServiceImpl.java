package com.aiolos.plaza.service.impl;

import com.aiolos.plaza.mapper.CartItemMapper;
import com.aiolos.plaza.model.po.CartItem;
import com.aiolos.plaza.service.CartItemService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class CartItemServiceImpl extends ServiceImpl<CartItemMapper, CartItem> implements CartItemService {
}

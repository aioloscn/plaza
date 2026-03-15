package com.aiolos.plaza.service.impl;

import com.aiolos.plaza.mapper.AddressMapper;
import com.aiolos.plaza.model.po.Address;
import com.aiolos.plaza.service.AddressService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class AddressServiceImpl extends ServiceImpl<AddressMapper, Address> implements AddressService {
}

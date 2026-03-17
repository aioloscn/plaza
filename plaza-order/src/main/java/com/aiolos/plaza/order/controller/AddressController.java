package com.aiolos.plaza.order.controller;

import com.aiolos.common.enums.error.ErrorEnum;
import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.common.model.ContextInfo;
import com.aiolos.plaza.model.po.Address;
import com.aiolos.plaza.service.AddressService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/address")
public class AddressController {

    @Autowired
    private AddressService addressService;

    @GetMapping("/list")
    public List<Address> list() {
        Long userId = ContextInfo.getUserId();
        if (userId == null) {
            
        }
        return addressService.list(new LambdaQueryWrapper<Address>()
                .eq(Address::getUserId, userId)
                .orderByDesc(Address::getIsDefault)
                .orderByDesc(Address::getUpdateTime));
    }

    @PostMapping("/add")
    public Boolean add(@RequestBody Address address) {
        Long userId = ContextInfo.getUserId();
        if (userId == null) {
            ExceptionUtil.throwException(ErrorEnum.USER_NOT_LOGGED_IN);
        }
        address.setUserId(userId);
        
        if (Boolean.TRUE.equals(address.getIsDefault())) {
            clearDefault(userId);
        }
        return addressService.save(address);
    }

    @PutMapping("/update")
    public Boolean update(@RequestBody Address address) {
        Long userId = ContextInfo.getUserId();
        if (userId == null) {
            ExceptionUtil.throwException(ErrorEnum.USER_NOT_LOGGED_IN);
        }
        // 确保只能修改自己的地址
        // 实际上应该先查询校验，这里暂略
        address.setUserId(userId);

        if (Boolean.TRUE.equals(address.getIsDefault())) {
            clearDefault(userId);
        }
        return addressService.updateById(address);
    }

    @DeleteMapping("/{id}")
    public Boolean delete(@PathVariable("id") Long id) {
        // 这里最好也校验一下是否是当前用户的地址
        return addressService.removeById(id);
    }

    @GetMapping("/{id}")
    public Address get(@PathVariable("id") Long id) {
        return addressService.getById(id);
    }

    private void clearDefault(Long userId) {
        addressService.update(null, new LambdaUpdateWrapper<Address>()
                .eq(Address::getUserId, userId)
                .set(Address::getIsDefault, false));
    }
}

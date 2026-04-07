package com.aiolos.plaza.order.controller;

import com.aiolos.common.enums.error.ErrorEnum;
import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.common.model.ContextInfo;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
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
        if (address == null || address.getId() == null) {
            ExceptionUtil.throwException(OrderExceptionEnum.ADDRESS_NOT_EXIST);
        }
        // 严格按 id + userId 校验归属，防止越权修改
        Address existing = addressService.getOne(new LambdaQueryWrapper<Address>()
                .eq(Address::getId, address.getId())
                .eq(Address::getUserId, userId)
                .last("LIMIT 1"));
        if (existing == null) {
            ExceptionUtil.throwException(OrderExceptionEnum.ADDRESS_NOT_EXIST);
        }
        address.setUserId(userId);

        if (Boolean.TRUE.equals(address.getIsDefault())) {
            clearDefault(userId);
        }
        return addressService.update(address, new LambdaUpdateWrapper<Address>()
                .eq(Address::getId, address.getId())
                .eq(Address::getUserId, userId));
    }

    @DeleteMapping("/{id}")
    public Boolean delete(@PathVariable("id") Long id) {
        Long userId = ContextInfo.getUserId();
        if (userId == null) {
            ExceptionUtil.throwException(ErrorEnum.USER_NOT_LOGGED_IN);
        }
        // 严格按 id + userId 删除，防止越权删除
        boolean removed = addressService.remove(new LambdaQueryWrapper<Address>()
                .eq(Address::getId, id)
                .eq(Address::getUserId, userId));
        if (!removed) {
            ExceptionUtil.throwException(OrderExceptionEnum.ADDRESS_NOT_EXIST);
        }
        return true;
    }

    @GetMapping("/{id}")
    public Address get(@PathVariable("id") Long id) {
        Long userId = ContextInfo.getUserId();
        if (userId == null) {
            ExceptionUtil.throwException(ErrorEnum.USER_NOT_LOGGED_IN);
        }
        // 严格按 id + userId 查询，防止越权读取
        Address address = addressService.getOne(new LambdaQueryWrapper<Address>()
                .eq(Address::getId, id)
                .eq(Address::getUserId, userId)
                .last("LIMIT 1"));
        if (address == null) {
            ExceptionUtil.throwException(OrderExceptionEnum.ADDRESS_NOT_EXIST);
        }
        return address;
    }

    private void clearDefault(Long userId) {
        addressService.update(null, new LambdaUpdateWrapper<Address>()
                .eq(Address::getUserId, userId)
                .set(Address::getIsDefault, false));
    }
}

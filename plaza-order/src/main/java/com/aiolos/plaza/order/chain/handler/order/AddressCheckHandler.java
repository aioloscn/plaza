package com.aiolos.plaza.order.chain.handler.order;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.mapper.AddressMapper;
import com.aiolos.plaza.model.po.Address;
import com.aiolos.plaza.order.chain.Chain;
import com.aiolos.plaza.order.chain.ChainHandler;
import com.aiolos.plaza.order.chain.context.OrderCreateContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AddressCheckHandler implements ChainHandler<OrderCreateContext> {

    @Autowired
    private AddressMapper addressMapper;

    @Override
    public void handle(OrderCreateContext context, Chain<OrderCreateContext> chain) {
        Address address = addressMapper.selectOne(new LambdaQueryWrapper<Address>()
                .eq(Address::getId, context.getReq().getAddressId())
                .eq(Address::getUserId, context.getUserId())
                .last("LIMIT 1"));
        if (address == null) {
            ExceptionUtil.throwException(OrderExceptionEnum.ADDRESS_NOT_EXIST);
        }
        context.setAddress(address);
        
        // 校验通过，继续执行下一个节点
        chain.proceed(context);
    }
}

package com.aiolos.plaza.mapper;

import com.aiolos.plaza.model.po.PaymentLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * <p>
 * 支付流水日志表 Mapper 接口
 * </p>
 */
@Mapper
public interface PaymentLogMapper extends BaseMapper<PaymentLog> {
}

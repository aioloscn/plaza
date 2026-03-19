package com.aiolos.plaza.model.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>
 * 支付流水日志表
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("payment_log")
public class PaymentLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 订单号(父订单号)
     */
    private String orderSn;

    /**
     * 支付方式：1->支付宝；2->微信
     */
    private Integer payType;

    /**
     * 第三方支付流水号
     */
    private String tradeNo;

    /**
     * 支付金额
     */
    private BigDecimal totalAmount;

    /**
     * 买家在支付平台的账号/ID
     */
    private String buyerId;

    /**
     * 支付成功时间
     */
    private LocalDateTime paymentTime;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}

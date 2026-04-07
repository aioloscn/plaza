package com.aiolos.plaza.model.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 支付补偿任务表实体
 */
@Data
@TableName("payment_compensation_task")
public class PaymentCompensationTask implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String taskNo;

    private String businessKey;

    private Integer compensationType;

    private String parentOrderSn;

    private String orderSn;

    private String tradeNo;

    private String refundRequestNo;

    private Integer status;

    private Integer retryCount;

    private Integer maxRetryCount;

    private LocalDateTime nextRetryTime;

    private String reasonCode;

    private String thirdPartyStatus;

    private String failReason;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

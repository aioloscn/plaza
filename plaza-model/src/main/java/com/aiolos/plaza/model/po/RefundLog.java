package com.aiolos.plaza.model.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 退款日志实体
 */
@Data
@TableName("refund_log")
public class RefundLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String refundRequestNo;

    private String actionType;

    private String actionStatus;

    private String requestPayload;

    private String responsePayload;

    private String message;

    private LocalDateTime createTime;
}

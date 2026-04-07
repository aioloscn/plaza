package com.aiolos.plaza.model.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 本地消息表实体
 */
@Data
@TableName("mq_local_message")
public class MqLocalMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 消息主题
     */
    private String topic;

    /**
     * 消息标签/扩展元数据
     * 预留扩展字段，避免继续承载核心治理语义
     */
    private String tag;

    /**
     * 消息类型：用于区分治理策略、清理周期与人工排障维度
     */
    private String messageType;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 状态：0-新建 1-发送成功 2-发送失败 3-发送中（任务抢占中）
     */
    private Integer state;

    /**
     * 重试次数
     */
    private Integer retryCount;

    /**
     * 下次可重试时间
     */
    private LocalDateTime nextRetryTime;

    /**
     * 最大重试次数
     */
    private Integer maxRetryCount;

    /**
     * 业务键（如订单号）
     */
    private String businessKey;

    /**
     * 最近一次失败原因
     */
    private String failReason;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}

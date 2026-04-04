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
     * 消息标签
     */
    private String tag;

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
     * 业务键（如订单号）
     */
    private String businessKey;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}

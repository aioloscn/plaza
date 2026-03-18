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
 * 父订单表
 * </p>
 *
 * @author aiolos
 * @since 2026-03-18
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("parent_order")
public class ParentOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 父订单编号（支付单号）
     */
    private String parentOrderSn;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 总金额
     */
    private BigDecimal totalAmount;

    /**
     * 应付总金额
     */
    private BigDecimal payAmount;

    /**
     * 订单状态：0->待付款；1->待发货；2->已发货；3->已完成；4->已关闭；5->无效订单
     */
    private Integer status;

    /**
     * 支付方式：1->支付宝；2->微信
     */
    private Integer payType;

    /**
     * 支付时间
     */
    private LocalDateTime paymentTime;

    /**
     * 删除状态：0->未删除；1->已删除
     */
    private Integer deleteStatus;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}

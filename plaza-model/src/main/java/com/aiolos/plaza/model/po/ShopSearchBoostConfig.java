package com.aiolos.plaza.model.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 门店搜索业务曝光权重配置
 * 运营系统通过商家级或店铺级配置干预搜索展现权重
 */
@Data
@TableName("shop_search_boost_config")
public class ShopSearchBoostConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 商家ID
     * 连锁投放场景使用，shopId 为空
     */
    private Long sellerId;

    /**
     * 店铺ID
     * 单店投放场景使用，sellerId 为空
     */
    private Long shopId;

    /**
     * 业务曝光权重
     */
    private BigDecimal boostWeight;

    /**
     * 状态：0-停用，1-启用
     */
    private Integer status;

    /**
     * 生效开始时间
     */
    private LocalDateTime startTime;

    /**
     * 生效结束时间
     */
    private LocalDateTime endTime;

    /**
     * 备注
     */
    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

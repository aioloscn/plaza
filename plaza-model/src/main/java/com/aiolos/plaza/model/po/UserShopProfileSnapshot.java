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
 * 用户门店画像快照表
 * 由 plaza-home 夜间任务全量重建并落库，查询链路可在 Redis 未命中时回源
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("user_shop_profile_snapshot")
public class UserShopProfileSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 长期偏好店铺ID列表(JSON数组) */
    private String favoriteShopIdsJson;

    /** 近7天活跃但非新店的店铺ID列表(JSON数组) */
    private String recentActiveShopIdsJson;

    /** 近7天首次出现的新店铺ID列表(JSON数组) */
    private String recentNewShopIdsJson;

    /** 店铺偏好强度映射(JSON对象，key=shopId, value=归一化强度) */
    private String shopPreferenceJson;

    /** 类目TopN兜底列表(JSON数组) */
    private String favoriteCategoryIdsJson;

    /** 类目偏好强度映射(JSON对象，key=categoryId, value=归一化强度) */
    private String categoryPreferenceJson;

    /** 用户可接受价格中心值 */
    private Integer avgPriceLevel;

    /** 价格偏好区间下界 */
    private Integer priceLowerBound;

    /** 价格偏好区间上界 */
    private Integer priceUpperBound;

    /** 价格容忍波动 */
    private Integer priceTolerance;

    /** 画像置信度，范围[0,1] */
    private Double profileConfidence;

    /** 长期店铺偏好原始强度映射(JSON对象) */
    private String shopStrengthRawJson;

    /** 近7天活跃店铺原始强度映射(JSON对象) */
    private String recentShopStrengthRawJson;

    /** 近7天新店原始强度映射(JSON对象) */
    private String recentNewShopStrengthRawJson;

    /** 类目原始强度映射(JSON对象) */
    private String categoryStrengthRawJson;

    /** 订单层价格中心值加权分子 */
    private BigDecimal payWeightedSumRaw;

    /** 订单层价格中心值加权分母 */
    private BigDecimal payWeightTotalRaw;

    /** 商品层价格画像加权分子 */
    private BigDecimal priceWeightedSumRaw;

    /** 商品层价格平方加权分子 */
    private BigDecimal priceWeightedSquareSumRaw;

    /** 商品层价格画像加权分母 */
    private BigDecimal priceWeightedFactorRaw;

    /** 已支付订单样本数 */
    private Integer paidOrderCountRaw;

    /** 已支付订单项样本数 */
    private Integer paidItemCountRaw;

    /** 近7天已支付订单样本数 */
    private Integer recentPaidOrderCountRaw;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}

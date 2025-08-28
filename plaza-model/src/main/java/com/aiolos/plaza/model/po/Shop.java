package com.aiolos.plaza.model.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>
 * 店铺表
 * </p>
 *
 * @author Aiolos
 * @since 2025-08-22
 */
@Getter
@Setter
@TableName("shop")
public class Shop implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 商家id
     */
    @TableField("seller_id")
    private Long sellerId;

    /**
     * 店铺名称
     */
    @TableField("name")
    private String name;

    /**
     * 品类id
     */
    @TableField("category_id")
    private Long categoryId;

    /**
     * icon地址
     */
    @TableField("icon_url")
    private String iconUrl;

    /**
     * 店铺评分
     */
    @TableField("score")
    private BigDecimal score;

    /**
     * 人均价格
     */
    @TableField("per_capita_price")
    private Integer perCapitaPrice;

    /**
     * 经度
     */
    @TableField("longitude")
    private BigDecimal longitude;

    /**
     * 纬度
     */
    @TableField("latitude")
    private BigDecimal latitude;

    /**
     * 详细地址
     */
    @TableField("address")
    private String address;

    /**
     * 店铺标签
     */
    @TableField("tags")
    private String tags;

    /**
     * 状态, 0: 禁用, 1: 启用
     */
    @TableField("status")
    private Integer status;

    /**
     * 开店时间
     */
    @TableField("opening_time")
    private LocalTime openingTime;

    /**
     * 结束营业时间
     */
    @TableField("closing_time")
    private LocalTime closingTime;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
    
    private Integer distance;

    public static final String ID = "id";

    public static final String SELLER_ID = "seller_id";

    public static final String NAME = "name";

    public static final String CATEGORY_ID = "category_id";

    public static final String ICON_URL = "icon_url";

    public static final String SCORE = "score";

    public static final String PER_CAPITA_PRICE = "per_capita_price";

    public static final String LONGITUDE = "longitude";

    public static final String LATITUDE = "latitude";

    public static final String ADDRESS = "address";

    public static final String TAGS = "tags";

    public static final String STATUS = "status";

    public static final String OPENING_TIME = "opening_time";

    public static final String CLOSING_TIME = "closing_time";

    public static final String CREATE_TIME = "create_time";

    public static final String UPDATE_TIME = "update_time";
}

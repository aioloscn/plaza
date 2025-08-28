package com.aiolos.plaza.model.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>
 * 商家表
 * </p>
 *
 * @author Aiolos
 * @since 2025-08-22
 */
@Getter
@Setter
@TableName("seller")
public class Seller implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 商家名称
     */
    @TableField("name")
    private String name;

    /**
     * 商家名下所有店铺的平均评分
     */
    @TableField("score")
    private BigDecimal score;

    /**
     * 状态, 0: 禁用, 1: 启用
     */
    @TableField("status")
    private Integer status;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

    public static final String ID = "id";

    public static final String NAME = "name";

    public static final String SCORE = "score";

    public static final String STATUS = "status";

    public static final String CREATE_TIME = "create_time";

    public static final String UPDATE_TIME = "update_time";
}

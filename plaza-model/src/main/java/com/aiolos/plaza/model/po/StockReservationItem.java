package com.aiolos.plaza.model.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("stock_reservation_item")
public class StockReservationItem implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("reservation_no")
    private String reservationNo;

    @TableField("order_sn")
    private String orderSn;

    @TableField("stock_scope")
    private Integer stockScope;

    @TableField("activity_id")
    private Long activityId;

    @TableField("sku_id")
    private Long skuId;

    @TableField("quantity")
    private Integer quantity;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}

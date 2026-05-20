package com.aiolos.plaza.product.model.bo;

import java.io.Serializable;
import lombok.Data;

@Data
public class ProductManagePageReq implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 页码，从1开始
     */
    private Long pageNum;

    /**
     * 每页条数
     */
    private Long pageSize;

    /**
     * 店铺ID
     */
    private Long shopId;

    /**
     * SPU状态
     */
    private Integer status;

    /**
     * 关键词，匹配SPU名称/SPU编码
     */
    private String keyword;

    /**
     * 发布业务线：1-外卖/即时零售，2-电商
     */
    private Integer bizType;
}

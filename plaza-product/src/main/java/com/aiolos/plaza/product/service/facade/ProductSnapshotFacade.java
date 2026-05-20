package com.aiolos.plaza.product.service.facade;

import com.aiolos.plaza.enums.ProductBizType;
import com.aiolos.plaza.product.model.dto.ProductCartSkuSnapshotDTO;
import com.aiolos.plaza.product.model.dto.ProductOrderSkuSnapshotDTO;
import java.util.List;
import java.util.Map;

public interface ProductSnapshotFacade {

    ProductCartSkuSnapshotDTO getCartSkuSnapshot(Long skuId, ProductBizType bizType);

    Map<Long, ProductCartSkuSnapshotDTO> batchGetCartSkuSnapshots(List<Long> skuIds, ProductBizType bizType);

    ProductOrderSkuSnapshotDTO getOrderSkuSnapshot(Long skuId, ProductBizType bizType);

    Map<Long, ProductOrderSkuSnapshotDTO> batchGetOrderSkuSnapshots(List<Long> skuIds, ProductBizType bizType);
}

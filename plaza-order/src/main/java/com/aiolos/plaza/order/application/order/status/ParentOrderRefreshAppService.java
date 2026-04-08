package com.aiolos.plaza.order.application.order.status;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.aiolos.plaza.order.domain.order.status.ParentStatusDomainService;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 父单状态刷新应用服务
 * 统一收口父单重算触发点，避免各条链路直接依赖领域重算实现
 */
@Service
public class ParentOrderRefreshAppService {

    private final ParentStatusDomainService parentStatusDomainService;

    public ParentOrderRefreshAppService(ParentStatusDomainService parentStatusDomainService) {
        this.parentStatusDomainService = parentStatusDomainService;
    }

    public void refresh(String parentOrderSn) {
        if (!StringUtils.hasText(parentOrderSn)) {
            return;
        }
        parentStatusDomainService.recomputeParentOrderStatus(parentOrderSn);
    }

    public void refreshAll(Collection<String> parentOrderSns) {
        if (parentOrderSns == null || parentOrderSns.isEmpty()) {
            return;
        }
        Set<String> uniqueParentOrderSns = new LinkedHashSet<>();
        for (String parentOrderSn : parentOrderSns) {
            if (StringUtils.hasText(parentOrderSn)) {
                uniqueParentOrderSns.add(parentOrderSn);
            }
        }
        uniqueParentOrderSns.forEach(parentStatusDomainService::recomputeParentOrderStatus);
    }
}

package com.aiolos.plaza.order.application.stock.reservation;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.StockLogType;
import com.aiolos.plaza.enums.StockScope;
import com.aiolos.plaza.enums.StockReservationEvent;
import com.aiolos.plaza.enums.StockReservationState;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.mapper.ProductStockAggregateMapper;
import com.aiolos.plaza.mapper.ProductStockLogMapper;
import com.aiolos.plaza.mapper.SeckillStockAggregateMapper;
import com.aiolos.plaza.mapper.StockReservationItemMapper;
import com.aiolos.plaza.mapper.StockReservationMapper;
import com.aiolos.plaza.model.po.ProductStockAggregate;
import com.aiolos.plaza.model.po.ProductStockLog;
import com.aiolos.plaza.model.po.SeckillStockAggregate;
import com.aiolos.plaza.model.po.StockReservation;
import com.aiolos.plaza.model.po.StockReservationItem;
import com.aiolos.plaza.order.domain.stock.reservation.InventoryReserveItem;
import com.aiolos.plaza.order.domain.stock.snapshot.InventoryProductSnapshot;
import com.aiolos.plaza.order.domain.stock.snapshot.ProductSnapshotReader;
import com.aiolos.plaza.order.domain.stock.reservation.StockReservationTransitionRule;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
/**
 * 库存预占应用服务实现
 * 统一处理预占生命周期：冻结、确认、释放、过期、退款回补
 */
public class StockReservationServiceImpl implements StockReservationService {

    @Resource
    private ProductStockAggregateMapper productStockAggregateMapper;

    @Resource
    private SeckillStockAggregateMapper seckillStockAggregateMapper;

    @Resource
    private ProductStockLogMapper productStockLogMapper;

    @Resource
    private StockReservationMapper stockReservationMapper;

    @Resource
    private StockReservationItemMapper stockReservationItemMapper;

    @Resource
    private StockReservationTransitionRule stockReservationTransitionRule;

    @Resource
    private ProductSnapshotReader productSnapshotReader;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public String reserve(String orderSn, Long userId, StockScope stockScope, Long activityId, List<InventoryReserveItem> items, LocalDateTime expireTime) {
        StockScope actualScope = stockScope == null ? StockScope.NORMAL : stockScope;
        if (actualScope == StockScope.SECKILL && activityId == null) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STATUS_ERROR);
        }
        String reservationNo = buildReservationNo(orderSn);
        // 幂等兜底：同一个订单号对应同一个 reservationNo，已冻结或已确认时直接返回
        StockReservation exists = stockReservationMapper.selectOne(new LambdaQueryWrapper<StockReservation>()
                .eq(StockReservation::getReservationNo, reservationNo));
        if (exists != null) {
            StockReservationState state = StockReservationState.fromCode(exists.getStatus());
            if (state == StockReservationState.FROZEN || state == StockReservationState.CONFIRMED) {
                return reservationNo;
            }
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STATUS_ERROR);
        }

        Map<String, MergedReserveItem> mergedItems = mergeItems(items, actualScope, activityId);
        LocalDateTime now = LocalDateTime.now();
        initNormalAggregateIfNeeded(actualScope, mergedItems, now);
        for (MergedReserveItem entry : mergedItems.values()) {
            freezeFromAvailable(actualScope, entry, now);
            saveStockLog(entry.productId, entry.activityId, actualScope, orderSn, -entry.quantity, StockLogType.RESERVE_FREEZE, now);
        }

        StockReservation reservation = new StockReservation();
        reservation.setReservationNo(reservationNo);
        reservation.setOrderSn(orderSn);
        reservation.setUserId(userId);
        reservation.setStockScope(actualScope.getCode());
        reservation.setActivityId(activityId);
        reservation.setStatus(StockReservationState.FROZEN.getCode());
        reservation.setExpireTime(expireTime);
        reservation.setCreateTime(now);
        reservation.setUpdateTime(now);
        stockReservationMapper.insert(reservation);

        List<StockReservationItem> reservationItems = new ArrayList<>();
        for (MergedReserveItem entry : mergedItems.values()) {
            StockReservationItem item = new StockReservationItem();
            item.setReservationNo(reservationNo);
            item.setOrderSn(orderSn);
            item.setStockScope(actualScope.getCode());
            item.setActivityId(entry.activityId);
            item.setProductId(entry.productId);
            item.setQuantity(entry.quantity);
            item.setCreateTime(now);
            item.setUpdateTime(now);
            reservationItems.add(item);
        }
        for (StockReservationItem item : reservationItems) {
            stockReservationItemMapper.insert(item);
        }
        return reservationNo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirm(String reservationNo) {
        // 仅 FROZEN -> CONFIRMED 合法，确认后库存进入已售维度
        StockReservation reservation = getByReservationNo(reservationNo);
        StockReservationState state = StockReservationState.fromCode(reservation.getStatus());
        // 幂等：已确认时直接返回
        if (state == StockReservationState.CONFIRMED) {
            return;
        }
        if (!stockReservationTransitionRule.canTransit(state, StockReservationEvent.CONFIRM)) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STATUS_ERROR);
        }

        int updated = stockReservationMapper.update(null, new LambdaUpdateWrapper<StockReservation>()
                .eq(StockReservation::getReservationNo, reservationNo)
                .eq(StockReservation::getStatus, state.getCode())
                .set(StockReservation::getStatus, StockReservationState.CONFIRMED.getCode())
                .set(StockReservation::getUpdateTime, LocalDateTime.now()));
        if (updated <= 0) {
            // 并发竞争下二次判定：若已被其他线程改成 CONFIRMED，视为成功
            StockReservation latest = getByReservationNo(reservationNo);
            if (StockReservationState.fromCode(latest.getStatus()) == StockReservationState.CONFIRMED) {
                return;
            }
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STATUS_ERROR);
        }

        List<StockReservationItem> items = stockReservationItemMapper.selectList(new LambdaQueryWrapper<StockReservationItem>()
                .eq(StockReservationItem::getReservationNo, reservationNo));
        StockScope stockScope = StockScope.fromCode(reservation.getStockScope());
        LocalDateTime now = LocalDateTime.now();
        for (StockReservationItem item : items) {
            // 确认支付后：冻结库存转确认库存，不再回到可用库存
            confirmFrozen(stockScope, item, now);
            saveStockLog(item.getProductId(), item.getActivityId(), stockScope, reservation.getOrderSn(), 0, StockLogType.PAY_CONFIRM, now);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void release(String reservationNo) {
        // 仅未确认预占允许释放，已确认库存需走退款回补
        StockReservation reservation = getByReservationNo(reservationNo);
        StockReservationState state = StockReservationState.fromCode(reservation.getStatus());
        // 幂等：已释放或已过期时，无需重复处理
        if (state == StockReservationState.RELEASED || state == StockReservationState.EXPIRED) {
            return;
        }
        // 已确认订单不允许走释放，避免把已售库存回补到可用库存
        if (state == StockReservationState.CONFIRMED) {
            return;
        }
        if (!stockReservationTransitionRule.canTransit(state, StockReservationEvent.RELEASE)) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STATUS_ERROR);
        }
        releaseTo(reservation, state, StockReservationState.RELEASED);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rollbackConfirmed(String reservationNo) {
        // 退款成功场景：把 confirmed 库存回补到 available
        StockReservation reservation = getByReservationNo(reservationNo);
        StockReservationState state = StockReservationState.fromCode(reservation.getStatus());
        // 幂等：已释放或已过期说明库存已回补完成，无需重复处理
        if (state == StockReservationState.RELEASED || state == StockReservationState.EXPIRED) {
            return;
        }
        if (state != StockReservationState.CONFIRMED) {
            // 退款补偿兜底：如果支付补偿失败前库存尚未确认，直接按普通释放回退冻结库存
            release(reservationNo);
            return;
        }

        int updated = stockReservationMapper.update(null, new LambdaUpdateWrapper<StockReservation>()
                .eq(StockReservation::getReservationNo, reservationNo)
                .eq(StockReservation::getStatus, StockReservationState.CONFIRMED.getCode())
                .set(StockReservation::getStatus, StockReservationState.RELEASED.getCode())
                .set(StockReservation::getUpdateTime, LocalDateTime.now()));
        if (updated <= 0) {
            StockReservation latest = getByReservationNo(reservationNo);
            StockReservationState latestState = StockReservationState.fromCode(latest.getStatus());
            if (latestState == StockReservationState.RELEASED || latestState == StockReservationState.EXPIRED) {
                return;
            }
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STATUS_ERROR);
        }

        List<StockReservationItem> items = stockReservationItemMapper.selectList(new LambdaQueryWrapper<StockReservationItem>()
                .eq(StockReservationItem::getReservationNo, reservationNo));
        StockScope stockScope = StockScope.fromCode(reservation.getStockScope());
        LocalDateTime now = LocalDateTime.now();
        for (StockReservationItem item : items) {
            rollbackConfirmedToAvailable(stockScope, item, now);
            saveStockLog(item.getProductId(), item.getActivityId(), stockScope, reservation.getOrderSn(), item.getQuantity(), StockLogType.REFUND_ROLLBACK, now);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void extendExpireTime(String reservationNo, LocalDateTime newExpireTime) {
        if (reservationNo == null || newExpireTime == null) {
            return;
        }
        StockReservation reservation = stockReservationMapper.selectOne(new LambdaQueryWrapper<StockReservation>()
                .eq(StockReservation::getReservationNo, reservationNo));
        if (reservation == null) {
            return;
        }
        StockReservationState state = StockReservationState.fromCode(reservation.getStatus());
        if (state != StockReservationState.FROZEN) {
            return;
        }
        if (reservation.getExpireTime() != null && !newExpireTime.isAfter(reservation.getExpireTime())) {
            return;
        }
        stockReservationMapper.update(null, new LambdaUpdateWrapper<StockReservation>()
                .eq(StockReservation::getReservationNo, reservationNo)
                .eq(StockReservation::getStatus, StockReservationState.FROZEN.getCode())
                .set(StockReservation::getExpireTime, newExpireTime)
                .set(StockReservation::getUpdateTime, LocalDateTime.now()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void expireReservations(int batchSize) {
        // 分批扫描，避免一次处理过多记录导致长事务
        int size = batchSize <= 0 ? 200 : batchSize;
        List<StockReservation> expiredReservations = stockReservationMapper.selectList(new LambdaQueryWrapper<StockReservation>()
                .eq(StockReservation::getStatus, StockReservationState.FROZEN.getCode())
                .le(StockReservation::getExpireTime, LocalDateTime.now())
                .last("limit " + size));
        for (StockReservation reservation : expiredReservations) {
            StockReservationState state = StockReservationState.fromCode(reservation.getStatus());
            if (state != StockReservationState.FROZEN) {
                continue;
            }
            try {
                releaseTo(reservation, state, StockReservationState.EXPIRED);
            } catch (Exception ex) {
                // 单条失败不影响后续任务，避免整批处理中断
                log.error("处理过期预占失败，reservationNo: {}", reservation.getReservationNo(), ex);
            }
        }
    }

    private void releaseTo(StockReservation reservation, StockReservationState fromState, StockReservationState toState) {
        String reservationNo = reservation.getReservationNo();
        // release 和 expire 统一收敛到同一回补逻辑，避免两套分支逐步漂移
        // CAS 状态迁移：仅当数据库状态仍是 `fromState` 时，才允许迁移到目标状态
        int updated = stockReservationMapper.update(null, new LambdaUpdateWrapper<StockReservation>()
                .eq(StockReservation::getReservationNo, reservationNo)
                .eq(StockReservation::getStatus, fromState.getCode())
                .set(StockReservation::getStatus, toState.getCode())
                .set(StockReservation::getUpdateTime, LocalDateTime.now()));
        if (updated <= 0) {
            // 并发下若目标已是 RELEASED/EXPIRED，按幂等成功处理
            StockReservation latest = getByReservationNo(reservationNo);
            StockReservationState latestState = StockReservationState.fromCode(latest.getStatus());
            if (latestState == StockReservationState.RELEASED || latestState == StockReservationState.EXPIRED) {
                return;
            }
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STATUS_ERROR);
        }

        List<StockReservationItem> items = stockReservationItemMapper.selectList(new LambdaQueryWrapper<StockReservationItem>()
                .eq(StockReservationItem::getReservationNo, reservationNo));
        StockScope stockScope = StockScope.fromCode(reservation.getStockScope());
        LocalDateTime now = LocalDateTime.now();
        StockLogType stockLogType = toState == StockReservationState.EXPIRED ? StockLogType.RESERVE_EXPIRE : StockLogType.RESERVE_RELEASE;
        for (StockReservationItem item : items) {
            // 释放或过期时：冻结库存回补到可用库存
            releaseFrozenToAvailable(stockScope, item, now);
            saveStockLog(item.getProductId(), item.getActivityId(), stockScope, reservation.getOrderSn(), item.getQuantity(), stockLogType, now);
        }
    }

    private StockReservation getByReservationNo(String reservationNo) {
        StockReservation reservation = stockReservationMapper.selectOne(new LambdaQueryWrapper<StockReservation>()
                .eq(StockReservation::getReservationNo, reservationNo));
        if (reservation == null) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_NOT_EXIST);
        }
        return reservation;
    }

    private Map<String, MergedReserveItem> mergeItems(List<InventoryReserveItem> items, StockScope stockScope, Long activityId) {
        Map<String, MergedReserveItem> result = new LinkedHashMap<>();
        for (InventoryReserveItem item : items) {
            if (item == null || item.getProductId() == null || item.getQuantity() == null || item.getQuantity() <= 0) {
                continue;
            }
            Long actualActivityId = stockScope == StockScope.SECKILL ? (item.getActivityId() == null ? activityId : item.getActivityId()) : null;
            if (stockScope == StockScope.SECKILL && actualActivityId == null) {
                continue;
            }
            String key = stockScope == StockScope.SECKILL ? actualActivityId + ":" + item.getProductId() : String.valueOf(item.getProductId());
            MergedReserveItem mergedItem = result.get(key);
            if (mergedItem == null) {
                result.put(key, new MergedReserveItem(item.getProductId(), actualActivityId, item.getQuantity()));
            } else {
                mergedItem.quantity = mergedItem.quantity + item.getQuantity();
            }
        }
        if (result.isEmpty()) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_NOT_EXIST);
        }
        return result;
    }

    private String buildReservationNo(String orderSn) {
        // 通过订单号派生预占号，保证同一个订单重复调用时保持幂等
        return "RSV-" + orderSn;
    }

    private void initNormalAggregateIfNeeded(StockScope stockScope, Map<String, MergedReserveItem> mergedItems, LocalDateTime now) {
        if (stockScope != StockScope.NORMAL) {
            return;
        }
        List<Long> productIds = new ArrayList<>();
        for (MergedReserveItem item : mergedItems.values()) {
            productIds.add(item.productId);
        }
        Map<Long, InventoryProductSnapshot> productSnapshotMap = productSnapshotReader.loadSnapshots(productIds);
        for (MergedReserveItem item : mergedItems.values()) {
            InventoryProductSnapshot product = productSnapshotMap.get(item.productId);
            if (product == null || product.getStatus() == null || product.getStatus() != 1) {
                ExceptionUtil.throwException(OrderExceptionEnum.PRODUCT_NOT_EXIST);
            }
            productStockAggregateMapper.initAggregate(item.productId, product.getStock(), now);
        }
    }

    private void freezeFromAvailable(StockScope stockScope, MergedReserveItem item, LocalDateTime now) {
        int rows;
        if (stockScope == StockScope.SECKILL) {
            seckillStockAggregateMapper.initAggregate(item.activityId, item.productId, now);
            rows = seckillStockAggregateMapper.update(null, new LambdaUpdateWrapper<SeckillStockAggregate>()
                    .eq(SeckillStockAggregate::getActivityId, item.activityId)
                    .eq(SeckillStockAggregate::getProductId, item.productId)
                    .ge(SeckillStockAggregate::getAvailableStock, item.quantity)
                    .setSql("available_stock = available_stock - " + item.quantity)
                    .setSql("frozen_stock = frozen_stock + " + item.quantity)
                    .setSql("version = version + 1")
                    .set(SeckillStockAggregate::getUpdateTime, now));
        } else {
            rows = productStockAggregateMapper.update(null, new LambdaUpdateWrapper<ProductStockAggregate>()
                    .eq(ProductStockAggregate::getProductId, item.productId)
                    .ge(ProductStockAggregate::getAvailableStock, item.quantity)
                    .setSql("available_stock = available_stock - " + item.quantity)
                    .setSql("frozen_stock = frozen_stock + " + item.quantity)
                    .setSql("version = version + 1")
                    .set(ProductStockAggregate::getUpdateTime, now));
        }
        if (rows <= 0) {
            ExceptionUtil.throwException(OrderExceptionEnum.STOCK_NOT_ENOUGH);
        }
    }

    private void confirmFrozen(StockScope stockScope, StockReservationItem item, LocalDateTime now) {
        int rows;
        if (stockScope == StockScope.SECKILL) {
            rows = seckillStockAggregateMapper.update(null, new LambdaUpdateWrapper<SeckillStockAggregate>()
                    .eq(SeckillStockAggregate::getActivityId, item.getActivityId())
                    .eq(SeckillStockAggregate::getProductId, item.getProductId())
                    .ge(SeckillStockAggregate::getFrozenStock, item.getQuantity())
                    .setSql("frozen_stock = frozen_stock - " + item.getQuantity())
                    .setSql("confirmed_stock = confirmed_stock + " + item.getQuantity())
                    .setSql("version = version + 1")
                    .set(SeckillStockAggregate::getUpdateTime, now));
        } else {
            rows = productStockAggregateMapper.update(null, new LambdaUpdateWrapper<ProductStockAggregate>()
                    .eq(ProductStockAggregate::getProductId, item.getProductId())
                    .ge(ProductStockAggregate::getFrozenStock, item.getQuantity())
                    .setSql("frozen_stock = frozen_stock - " + item.getQuantity())
                    .setSql("confirmed_stock = confirmed_stock + " + item.getQuantity())
                    .setSql("version = version + 1")
                    .set(ProductStockAggregate::getUpdateTime, now));
        }
        if (rows <= 0) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STOCK_RELEASE_FAIL);
        }
    }

    private void releaseFrozenToAvailable(StockScope stockScope, StockReservationItem item, LocalDateTime now) {
        int rows;
        if (stockScope == StockScope.SECKILL) {
            rows = seckillStockAggregateMapper.update(null, new LambdaUpdateWrapper<SeckillStockAggregate>()
                    .eq(SeckillStockAggregate::getActivityId, item.getActivityId())
                    .eq(SeckillStockAggregate::getProductId, item.getProductId())
                    .ge(SeckillStockAggregate::getFrozenStock, item.getQuantity())
                    .setSql("available_stock = available_stock + " + item.getQuantity())
                    .setSql("frozen_stock = frozen_stock - " + item.getQuantity())
                    .setSql("version = version + 1")
                    .set(SeckillStockAggregate::getUpdateTime, now));
        } else {
            rows = productStockAggregateMapper.update(null, new LambdaUpdateWrapper<ProductStockAggregate>()
                    .eq(ProductStockAggregate::getProductId, item.getProductId())
                    .ge(ProductStockAggregate::getFrozenStock, item.getQuantity())
                    .setSql("available_stock = available_stock + " + item.getQuantity())
                    .setSql("frozen_stock = frozen_stock - " + item.getQuantity())
                    .setSql("version = version + 1")
                    .set(ProductStockAggregate::getUpdateTime, now));
        }
        if (rows <= 0) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STOCK_RELEASE_FAIL);
        }
    }

    private void rollbackConfirmedToAvailable(StockScope stockScope, StockReservationItem item, LocalDateTime now) {
        int rows;
        if (stockScope == StockScope.SECKILL) {
            rows = seckillStockAggregateMapper.update(null, new LambdaUpdateWrapper<SeckillStockAggregate>()
                    .eq(SeckillStockAggregate::getActivityId, item.getActivityId())
                    .eq(SeckillStockAggregate::getProductId, item.getProductId())
                    .ge(SeckillStockAggregate::getConfirmedStock, item.getQuantity())
                    .setSql("available_stock = available_stock + " + item.getQuantity())
                    .setSql("confirmed_stock = confirmed_stock - " + item.getQuantity())
                    .setSql("version = version + 1")
                    .set(SeckillStockAggregate::getUpdateTime, now));
        } else {
            rows = productStockAggregateMapper.update(null, new LambdaUpdateWrapper<ProductStockAggregate>()
                    .eq(ProductStockAggregate::getProductId, item.getProductId())
                    .ge(ProductStockAggregate::getConfirmedStock, item.getQuantity())
                    .setSql("available_stock = available_stock + " + item.getQuantity())
                    .setSql("confirmed_stock = confirmed_stock - " + item.getQuantity())
                    .setSql("version = version + 1")
                    .set(ProductStockAggregate::getUpdateTime, now));
        }
        if (rows <= 0) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_STOCK_RELEASE_FAIL);
        }
    }

    private void saveStockLog(Long productId, Long activityId, StockScope stockScope, String orderSn, Integer amount, StockLogType type, LocalDateTime now) {
        ProductStockLog stockLog = new ProductStockLog();
        stockLog.setProductId(productId);
        stockLog.setActivityId(activityId);
        stockLog.setStockScope(stockScope.getCode());
        stockLog.setOrderSn(orderSn);
        stockLog.setAmount(amount);
        stockLog.setType(type.getCode());
        stockLog.setCreateTime(now);
        productStockLogMapper.insert(stockLog);
    }

    private static class MergedReserveItem {
        private final Long productId;
        private final Long activityId;
        private Integer quantity;

        private MergedReserveItem(Long productId, Long activityId, Integer quantity) {
            this.productId = productId;
            this.activityId = activityId;
            this.quantity = quantity;
        }
    }
}

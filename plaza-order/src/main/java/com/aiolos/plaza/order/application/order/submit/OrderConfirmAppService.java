package com.aiolos.plaza.order.application.order.submit;

import com.aiolos.common.exception.util.ExceptionUtil;
import com.aiolos.plaza.enums.RedisKeyEnum;
import com.aiolos.plaza.enums.exceptions.OrderExceptionEnum;
import com.aiolos.plaza.mapper.AddressMapper;
import com.aiolos.plaza.mapper.CartItemMapper;
import com.aiolos.plaza.model.po.Address;
import com.aiolos.plaza.model.po.CartItem;
import com.aiolos.plaza.order.domain.order.snapshot.OrderProductSnapshotLoader;
import com.aiolos.plaza.order.domain.stock.snapshot.InventoryProductSnapshot;
import com.aiolos.plaza.order.model.bo.OrderSubmitReq;
import com.aiolos.plaza.order.model.vo.OrderConfirmVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 订单确认页应用服务
 * 负责确认快照生成、confirmToken 签发和提交前二次校验
 */
@Service
public class OrderConfirmAppService {

    @Autowired
    private AddressMapper addressMapper;

    @Autowired
    private CartItemMapper cartItemMapper;

    @Autowired
    private OrderProductSnapshotLoader orderProductSnapshotLoader;

    @Autowired
    @Qualifier("orderRedisTemplate")
    private StringRedisTemplate orderRedisTemplate;

    public OrderConfirmVO confirm(Long userId, OrderSubmitReq req) {
        // 每次确认都实时重建一份校验快照，保证价格/库存/上下架状态是最新视图
        PrecheckSnapshot snapshot = buildPrecheckSnapshot(userId, req);
        OrderConfirmVO vo = new OrderConfirmVO();
        vo.setTotalAmount(snapshot.totalAmount);
        vo.setItemCount(snapshot.items.size());
        vo.setItems(snapshot.items);
        vo.setReady(snapshot.ready);
        if (snapshot.ready) {
            // 仅当确认页校验通过时才签发 token，避免无效快照进入提交链路
            String token = UUID.randomUUID().toString().replace("-", "");
            String key = RedisKeyEnum.ORDER_CONFIRM_TOKEN.getKey(userId, token);
            // token 只保存快照指纹，不保存明细，提交时重算指纹对比
            orderRedisTemplate.opsForValue().set(
                    key,
                    snapshot.fingerprint,
                    RedisKeyEnum.ORDER_CONFIRM_TOKEN.getDefaultExpireSeconds(),
                    TimeUnit.SECONDS
            );
            vo.setConfirmToken(token);
        }
        return vo;
    }

    public void validateConfirmToken(Long userId, OrderSubmitReq req) {
        if (req == null || !StringUtils.hasText(req.getConfirmToken())) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_CONFIRM_INVALID);
        }
        String key = RedisKeyEnum.ORDER_CONFIRM_TOKEN.getKey(userId, req.getConfirmToken());
        // token 不存在或过期，直接判定为非法提交
        String fingerprint = orderRedisTemplate.opsForValue().get(key);
        if (!StringUtils.hasText(fingerprint)) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_CONFIRM_INVALID);
        }
        // 提交时重算快照，确保用户提交内容与确认页看到的内容完全一致
        PrecheckSnapshot snapshot = buildPrecheckSnapshot(userId, req);
        if (!snapshot.ready || !fingerprint.equals(snapshot.fingerprint)) {
            ExceptionUtil.throwException(OrderExceptionEnum.ORDER_CONFIRM_INVALID);
        }
        // 一次性 token，校验通过后立即删除，避免重放
        orderRedisTemplate.delete(key);
    }

    private PrecheckSnapshot buildPrecheckSnapshot(Long userId, OrderSubmitReq req) {
        // 地址必须属于当前用户，避免越权提交
        LambdaQueryWrapper<Address> addressQuery = new LambdaQueryWrapper<>();
        addressQuery.eq(Address::getId, req.getAddressId()).eq(Address::getUserId, userId);
        Address address = addressMapper.selectOne(addressQuery);
        if (address == null) {
            ExceptionUtil.throwException(OrderExceptionEnum.ADDRESS_NOT_EXIST);
        }

        LambdaQueryWrapper<CartItem> cartQuery = new LambdaQueryWrapper<>();
        cartQuery.eq(CartItem::getUserId, userId).eq(CartItem::getChecked, 1);
        if (req.getShopId() != null) {
            cartQuery.eq(CartItem::getShopId, req.getShopId());
        }
        List<CartItem> cartItems = cartItemMapper.selectList(cartQuery);
        if (cartItems == null || cartItems.isEmpty()) {
            ExceptionUtil.throwException(OrderExceptionEnum.CART_EMPTY);
        }

        // 批量读取商品快照，减少逐条查询成本并统一校验口径
        Map<String, InventoryProductSnapshot> productMap = orderProductSnapshotLoader.loadSnapshots(cartItems);

        List<OrderConfirmVO.ItemResult> results = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;
        boolean ready = true;
        StringBuilder fingerprintSource = new StringBuilder();
        fingerprintSource.append("u=").append(userId).append("|a=").append(req.getAddressId()).append("|s=").append(req.getShopId());

        // 固定遍历顺序，保证同一购物车内容生成稳定指纹
        List<CartItem> sortedItems = new ArrayList<>(cartItems);
        sortedItems.sort(Comparator.comparing(CartItem::getId));
        for (CartItem item : sortedItems) {
            InventoryProductSnapshot product = productMap.get(OrderProductSnapshotLoader.buildSnapshotKey(item.getBizType(), item.getSkuId()));
            OrderConfirmVO.ItemResult result = new OrderConfirmVO.ItemResult();
            result.setCartItemId(item.getId());
            result.setSkuId(item.getSkuId());
            result.setShopId(item.getShopId());
            result.setProductName(item.getProductName());
            result.setQuantity(item.getQuantity());
            result.setCartPrice(item.getPriceSnapshot());
            result.setValid(true);

            if (product == null) {
                result.setValid(false);
                result.setReasonCode("PRODUCT_NOT_EXIST");
                result.setReasonMsg("商品不存在");
                ready = false;
                results.add(result);
                fingerprintSource.append("|i=").append(item.getId()).append(":missing");
                continue;
            }

            result.setProductName(product.getProductName());
            result.setCurrentPrice(product.getPrice());
            result.setAvailableStock(product.getStock());

            if (!Objects.equals(product.getStatus(), 1)) {
                result.setValid(false);
                result.setReasonCode("PRODUCT_OFFLINE");
                result.setReasonMsg("商品已下架");
                ready = false;
            } else if (item.getQuantity() == null || item.getQuantity() <= 0) {
                result.setValid(false);
                result.setReasonCode("INVALID_QUANTITY");
                result.setReasonMsg("购买数量非法");
                ready = false;
            } else if (product.getStock() == null || product.getStock() < item.getQuantity()) {
                result.setValid(false);
                result.setReasonCode("STOCK_NOT_ENOUGH");
                result.setReasonMsg("库存不足");
                ready = false;
            } else if (item.getPriceSnapshot() == null || product.getPrice() == null || item.getPriceSnapshot().compareTo(product.getPrice()) != 0) {
                result.setValid(false);
                result.setReasonCode("PRICE_CHANGED");
                result.setReasonMsg("商品价格已变动");
                ready = false;
            }

            if (product.getPrice() != null && item.getQuantity() != null && item.getQuantity() > 0) {
                totalAmount = totalAmount.add(product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            }

            results.add(result);
            fingerprintSource.append("|i=").append(item.getId())
                    .append(":s=").append(item.getSkuId())
                    .append(",b=").append(item.getBizType())
                    .append(",q=").append(item.getQuantity())
                    .append(",cp=").append(item.getPriceSnapshot())
                    .append(",np=").append(product.getPrice())
                    .append(",st=").append(product.getStatus())
                    .append(",sk=").append(product.getStock());
        }

        PrecheckSnapshot snapshot = new PrecheckSnapshot();
        snapshot.ready = ready;
        snapshot.totalAmount = totalAmount;
        snapshot.items = results;
        // 指纹汇总了关键可变字段，用于提交时做一致性校验
        snapshot.fingerprint = sha256Hex(fingerprintSource.toString());
        return snapshot;
    }

    private String sha256Hex(String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static class PrecheckSnapshot {
        private boolean ready;
        private String fingerprint;
        private BigDecimal totalAmount;
        private List<OrderConfirmVO.ItemResult> items;
    }
}

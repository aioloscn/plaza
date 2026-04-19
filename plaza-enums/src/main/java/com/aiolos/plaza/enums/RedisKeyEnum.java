package com.aiolos.plaza.enums;

/**
 * 统一管理 Redis Key，防止硬编码导致的锁失效或缓存冲突
 * 包含 Key 的模板定义以及默认过期时间
 */
public enum RedisKeyEnum {

    // ========== 购物车模块 ==========
    /** 用户购物车 Hash (长期有效) */
    CART_USER("cart:user:%s", -1L),
    
    /** 临时(游客)购物车 Hash (默认7天过期) */
    CART_TEMP("cart:temp:%s", 7 * 24 * 3600L),
    
    /** 购物车清空防幽灵标记 String (过期时间60s) */
    CART_EMPTY_MARK_USER("cart:empty_mark:user:%s", 60L),
    
    /** 临时购物车清空防幽灵标记 String (过期时间60s) */
    CART_EMPTY_MARK_TEMP("cart:empty_mark:temp:%s", 60L),

    // ========== 商品模块 ==========
    /** 商品详情缓存 String (过期时间24小时) */
    PRODUCT_INFO("product:info:%s", 24 * 3600L),
    
    /** 商品实时库存 String (长期有效) */
    PRODUCT_STOCK("product:stock:%s", -1L),

    // ========== 分布式锁 ==========
    /** Canal Leader 锁 (仅允许一个节点消费Canal) */
    LOCK_CANAL_LEADER("lock:canal:leader", 30L),

    /** Canal 同步任务全局锁 (锁续期最大5分钟) */
    LOCK_CANAL_RUN("lock:canal:run", 300L),
    
    /** 店铺索引更新锁 (短锁10秒) */
    LOCK_SHOP_INDEX("lock:shop:index:%s", 10L),

    /** 店铺ES全量同步任务锁 (锁续期最大30分钟) */
    LOCK_SHOP_FULL_SYNC("lock:shop:full_sync", 1800L),
    
    /** 商品库存扣减锁 (用于Redisson锁商品) */
    LOCK_STOCK("lock:stock:%s", 10L),

    // ========== 秒杀模块 ==========
    /** 秒杀活动库存 String (长期有效，由预热写入) */
    SECKILL_STOCK("seckill:stock:%s", -1L),

    /** 秒杀活动价格 String (长期有效，由预热写入) */
    SECKILL_PRICE("seckill:price:%s", -1L),

    /** 单个秒杀活动详情缓存 String (长期有效，由预热写入) */
    SECKILL_ACTIVITY_INFO("seckill:info:%s", -1L),

    /** 店铺的秒杀活动列表缓存 String (过期时间24小时) */
    SECKILL_SHOP_LIST("seckill:shop:list:%s", 24 * 3600L),

    /** 已抢购成功的用户集合 Set (防止重复抢购) */
    SECKILL_BOUGHT_USERS("seckill:bought_users:%s", -1L),

    /** 单个用户秒杀防刷频控标记 (如10秒过期) */
    SECKILL_LIMIT("seckill:limit:%s", 10L),

    /** 首页门店检索用户画像缓存 String (默认36小时，覆盖夜间全量重建间隔) */
    HOME_USER_PROFILE("home:user:profile:%s", 36 * 3600L),

    /** 秒杀订单确认令牌缓存 String (默认10分钟) */
    ORDER_CONFIRM_TOKEN("order:confirm:token:%s:%s", 600L);

    private final String keyTemplate;
    private final Long defaultExpireSeconds;

    RedisKeyEnum(String keyTemplate, Long defaultExpireSeconds) {
        this.keyTemplate = keyTemplate;
        this.defaultExpireSeconds = defaultExpireSeconds;
    }

    /**
     * 动态生成具体的 Redis Key
     * @param args 占位符参数（如 userId, productId, deviceId 等）
     * @return 格式化后的 Redis Key
     */
    public String getKey(Object... args) {
        return String.format(keyTemplate, args);
    }

    public Long getDefaultExpireSeconds() {
        return defaultExpireSeconds;
    }
}

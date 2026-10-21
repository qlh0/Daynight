package com.sky.constant;

/**
 * Redis 相关常量
 */
public class RedisConstants {

    /* ==================== 菜品分类缓存 ==================== */

    //菜品分类列表缓存的key前缀，完整key：dish_{categoryId}
    public static final String DISH_LIST_KEY = "dish_";

    //空值缓存标记，用于防止缓存穿透；不能用null，Redis存不了null
    public static final String EMPTY_CACHE = "NULL";

    //正常数据的缓存时间（分钟）
    public static final long DISH_CACHE_TTL_MINUTES = 30L;

    //随机TTL的抖动上限（分钟），实际TTL = 30 ~ 40分钟，用于防止缓存雪崩
    public static final long DISH_CACHE_JITTER_MINUTES = 10L;

    //空值缓存的过期时间（分钟），要比正常数据短，避免脏数据长期占位
    public static final long EMPTY_CACHE_TTL_MINUTES = 2L;

    /* ==================== 互斥锁 ==================== */

    //互斥锁key前缀，完整key：lock:dish_{categoryId}
    public static final String LOCK_DISH_KEY = "lock:dish:";

    //锁的自动过期时间（秒），防止拿到锁的线程宕机导致死锁
    public static final long LOCK_TTL_SECONDS = 10L;

    //没抢到锁时的最大重试次数
    public static final int LOCK_MAX_RETRY = 3;

    //没抢到锁时的重试间隔（毫秒）
    public static final long LOCK_RETRY_INTERVAL_MS = 50L;

    /* ==================== 全局唯一ID ==================== */

    //自增序列key前缀，完整key：icr:{业务}:{yyyyMMdd}
    public static final String ID_KEY_PREFIX = "icr:";

    //订单业务前缀
    public static final String ID_PREFIX_ORDER = "order";

    //秒杀订单业务前缀
    public static final String ID_PREFIX_SECKILL = "seckill";

    /* ==================== 秒杀 ==================== */

    //秒杀库存key前缀，完整key：seckill:stock:{seckillId}
    public static final String SECKILL_STOCK_KEY = "seckill:stock:";

    //已抢购用户集合key前缀，完整key：seckill:buyers:{seckillId}
    public static final String SECKILL_BUYERS_KEY = "seckill:buyers:";

    //异步下单结果key前缀，完整key：seckill:result:{orderNo}
    public static final String SECKILL_RESULT_KEY = "seckill:result:";

    //阻塞队列容量，队列满了直接拒绝，起到限流作用
    public static final int SECKILL_QUEUE_CAPACITY = 5000;

    //异步消费者线程数
    public static final int SECKILL_CONSUMER_COUNT = 4;

    /* ==================== 接口限流 ==================== */

    //限流key前缀，完整key：rate:limit:{方法名}:{身份}
    public static final String RATE_LIMIT_KEY = "rate:limit:";

    /* ==================== 接口幂等 ==================== */

    //幂等token key前缀，完整key：idem:token:{userId}:{token}
    public static final String IDEMPOTENT_TOKEN_KEY = "idem:token:";

    //幂等token的有效期（秒），超时未使用自动失效
    public static final long IDEMPOTENT_TOKEN_TTL_SECONDS = 300L;

    //幂等token在请求头中的字段名
    public static final String IDEMPOTENT_TOKEN_HEADER = "idempotent-token";
}

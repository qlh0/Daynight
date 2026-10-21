package com.sky.support;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;
import java.util.function.Supplier;

/**
 * 测试数据工具：负责把秒杀相关的数据恢复到初始状态
 * <p>
 * 注意两件事：
 * 1. 数据库和 Redis 两边都要重置，否则数据库有库存而 Redis 没库存（或反之），测试结果不可信
 * 2. Redis 的库存key只在应用启动时预热，测试里必须手动写入
 */
public class TestDataHelper {

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_BUYERS_KEY = "seckill:buyers:";
    public static final String SECKILL_RESULT_KEY = "seckill:result:";

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;

    public TestDataHelper(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 重置秒杀活动：数据库库存 + Redis库存 + 清除买入记录和订单
     */
    public void resetSeckill(Long seckillId, int stock) {
        //1. 清理历史秒杀订单
        jdbcTemplate.update("delete from order_detail where order_id in " +
                "(select id from orders where remark = '秒杀订单')");
        jdbcTemplate.update("delete from orders where remark = '秒杀订单'");

        //2. 数据库库存恢复
        jdbcTemplate.update("update seckill_dish set stock = ? where id = ?", stock, seckillId);

        //3. Redis库存与购买记录清理
        redisTemplate.delete(SECKILL_STOCK_KEY + seckillId);
        redisTemplate.delete(SECKILL_BUYERS_KEY + seckillId);
        clearByPattern(SECKILL_RESULT_KEY + "*");

        //4. 重新写入Redis库存（模拟应用启动时的预热）
        redisTemplate.opsForValue().set(SECKILL_STOCK_KEY + seckillId, String.valueOf(stock));
    }

    /**
     * 清理菜品缓存，让下一次查询必定回源
     */
    public void clearDishCache() {
        clearByPattern("dish_*");
    }

    private void clearByPattern(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    public int queryStock(Long seckillId) {
        Integer stock = jdbcTemplate.queryForObject(
                "select stock from seckill_dish where id = ?", Integer.class, seckillId);
        return stock == null ? -1 : stock;
    }

    public int countSeckillOrders() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from orders where remark = '秒杀订单'", Integer.class);
        return count == null ? 0 : count;
    }

    public String queryRedis(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * 清理限流计数器，避免上一个用例消耗的额度影响下一个用例
     */
    public void clearRateLimit() {
        clearByPattern("rate:limit:*");
    }

    /**
     * 清理幂等token
     */
    public void clearIdempotent() {
        clearByPattern("idem:token:*");
    }

    /**
     * 重置某个用户的订单相关数据：订单、明细、地址、购物车
     */
    public void resetUserOrderData(long userId) {
        jdbcTemplate.update("delete from order_detail where order_id in " +
                "(select id from orders where user_id = ?)", userId);
        jdbcTemplate.update("delete from orders where user_id = ?", userId);
        jdbcTemplate.update("delete from address_book where user_id = ?", userId);
        jdbcTemplate.update("delete from shopping_cart where user_id = ?", userId);
    }

    /**
     * 给用户造一条默认地址，返回地址id
     */
    public long createDefaultAddress(long userId) {
        jdbcTemplate.update("insert into address_book " +
                        "(user_id, consignee, sex, phone, detail, is_default) values (?,?,?,?,?,?)",
                userId, "测试收货人", "1", "13800000000", "测试地址1号", 1);
        Long id = jdbcTemplate.queryForObject(
                "select id from address_book where user_id = ? order by id desc limit 1",
                Long.class, userId);
        return id == null ? 0L : id;
    }

    /**
     * 给用户购物车塞一条菜品，用作下单前置数据
     */
    public void createShoppingCartItem(long userId, long dishId, String name, String amount) {
        jdbcTemplate.update("insert into shopping_cart " +
                        "(name, image, user_id, dish_id, number, amount, create_time) " +
                        "values (?,?,?,?,?,?,now())",
                name, "test.png", userId, dishId, 1, new java.math.BigDecimal(amount));
    }

    /**
     * 轮询等待异步结果，超时抛断言错误
     */
    public static void awaitUntil(String description, long timeoutMs, Supplier<Boolean> condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.get())) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待被中断", e);
            }
        }
        throw new AssertionError("等待超时：" + description);
    }
}

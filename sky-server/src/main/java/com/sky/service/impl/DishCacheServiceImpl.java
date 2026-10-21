package com.sky.service.impl;

import com.sky.constant.RedisConstants;
import com.sky.constant.StatusConstant;
import com.sky.entity.Dish;
import com.sky.service.DishCacheService;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 菜品缓存实现
 * <p>
 * 针对缓存的三个经典问题分别做了处理：
 * 1. 缓存穿透：数据库查不到时写入短TTL的空值标记
 * 2. 缓存雪崩：写入时使用 30~40 分钟的随机TTL
 * 3. 缓存击穿：回源前用 setIfAbsent 抢互斥锁，同一分类只有一个线程查库
 */
@Service
@Slf4j
public class DishCacheServiceImpl implements DishCacheService {

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private DishService dishService;

    @Override
    public List<DishVO> listByCategory(Long categoryId) {
        return listByCategory(categoryId, 0);
    }

    /**
     * @param retry 当前重试次数
     */
    private List<DishVO> listByCategory(Long categoryId, int retry) {
        String key = RedisConstants.DISH_LIST_KEY + categoryId;

        //1. 先查缓存
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            log.info("[缓存命中] key={}", key);
            return unwrap(cached);
        }

        //2. 没命中，抢互斥锁，防止热点key击穿
        String lockKey = RedisConstants.LOCK_DISH_KEY + categoryId;
        String lockValue = UUID.randomUUID().toString();
        Boolean locked = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, lockValue, RedisConstants.LOCK_TTL_SECONDS, TimeUnit.SECONDS);

        if (!Boolean.TRUE.equals(locked)) {
            //没抢到锁：最多等3次，仍然抢不到就降级直查数据库，避免请求堆积
            if (retry >= RedisConstants.LOCK_MAX_RETRY) {
                log.warn("[互斥锁重试超限] 降级直查数据库, categoryId={}", categoryId);
                return queryFromDb(categoryId);
            }
            log.info("[未获取锁] 等待后重试, categoryId={}, retry={}", categoryId, retry + 1);
            sleep(RedisConstants.LOCK_RETRY_INTERVAL_MS);
            return listByCategory(categoryId, retry + 1);
        }

        try {
            //3. 双重检查：可能别的线程刚把数据写进缓存
            Object again = redisTemplate.opsForValue().get(key);
            if (again != null) {
                log.info("[缓存命中-二次检查] key={}", key);
                return unwrap(again);
            }
            return queryFromDbAndCache(categoryId, key);
        } finally {
            releaseLock(lockKey, lockValue);
        }
    }

    /**
     * 回源并写缓存
     */
    private List<DishVO> queryFromDbAndCache(Long categoryId, String key) {
        List<DishVO> list = queryFromDb(categoryId);

        if (list == null || list.isEmpty()) {
            //缓存穿透：把空结果也缓存下来，TTL要短
            redisTemplate.opsForValue().set(key, RedisConstants.EMPTY_CACHE,
                    RedisConstants.EMPTY_CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            log.info("[缓存空值] key={}, ttl={}分钟", key, RedisConstants.EMPTY_CACHE_TTL_MINUTES);
            return Collections.emptyList();
        }

        //缓存雪崩：TTL 加随机抖动
        long ttl = RedisConstants.DISH_CACHE_TTL_MINUTES
                + ThreadLocalRandom.current().nextLong(RedisConstants.DISH_CACHE_JITTER_MINUTES);
        redisTemplate.opsForValue().set(key, list, ttl, TimeUnit.MINUTES);
        log.info("[回源写缓存] key={}, ttl={}分钟, size={}", key, ttl, list.size());
        return list;
    }

    private List<DishVO> queryFromDb(Long categoryId) {
        Dish dish = Dish.builder()
                .categoryId(categoryId)
                .status(StatusConstant.ENABLE)
                .build();
        return dishService.listWithFlavor(dish);
    }

    /**
     * 取出缓存中的对象，空值标记转成空集合
     */
    @SuppressWarnings("unchecked")
    private List<DishVO> unwrap(Object cached) {
        if (RedisConstants.EMPTY_CACHE.equals(cached)) {
            log.info("[命中空值缓存] 直接返回空集合");
            return Collections.emptyList();
        }
        return (List<DishVO>) cached;
    }

    /**
     * 释放锁：只释放自己加的锁，避免误删别人的
     */
    private void releaseLock(String lockKey, String lockValue) {
        Object current = redisTemplate.opsForValue().get(lockKey);
        if (lockValue.equals(current)) {
            redisTemplate.delete(lockKey);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void evictCategory(Long categoryId) {
        if (categoryId == null) {
            return;
        }
        redisTemplate.delete(RedisConstants.DISH_LIST_KEY + categoryId);
        log.info("[清理分类缓存] categoryId={}", categoryId);
    }

    @Override
    public void evictAll() {
        Set<String> keys = scan(RedisConstants.DISH_LIST_KEY + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.info("[清理全部菜品缓存] 共{}个key", keys.size());
        }
    }

    /**
     * 用 SCAN 代替 KEYS，避免大key场景下阻塞Redis
     */
    private Set<String> scan(String pattern) {
        Set<String> keys = new HashSet<>();
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(1000).build();
        redisTemplate.execute((RedisCallback<Object>) connection -> {
            try (Cursor<byte[]> cursor = connection.scan(options)) {
                while (cursor.hasNext()) {
                    keys.add((String) redisTemplate.getKeySerializer().deserialize(cursor.next()));
                }
            }
            return null;
        });
        return keys;
    }
}

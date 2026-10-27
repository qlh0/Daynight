package com.sky.service.impl;

import com.sky.constant.RedisConstants;
import com.sky.constant.StatusConstant;
import com.sky.entity.Dish;
import com.sky.service.DishCacheService;
import com.sky.service.DishService;
import com.sky.vo.DishVO;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 菜品缓存实现（Redisson 版本）
 * <p>
 * 三件套仍然保留：
 * 1. 缓存穿透：查不到时写短 TTL 的 "NULL" 标记
 * 2. 缓存雪崩：TTL = 30 + random(0,10) 分钟
 * 3. 缓存击穿：Redisson 互斥锁（RLock），同一分类只有一个线程回源
 * <p>
 * 一致性由 Redisson 读写锁保证：
 * - 回源重建缓存时持有「读锁」
 * - 管理端更新数据库 + 删除缓存时持有「写锁」
 * 两者互斥，避免"读线程读到旧值 → 写线程更新库并删缓存 → 读线程把旧值写回缓存"的经典竞态。
 */
@Service
@Slf4j
public class DishCacheServiceImpl implements DishCacheService {

    @Autowired
    private RedisTemplate redisTemplate;

    @Autowired
    private DishService dishService;

    @Autowired
    private RedissonClient redissonClient;

    @Override
    public List<DishVO> listByCategory(Long categoryId) {
        return listByCategory(categoryId, 0);
    }

    /**
     * @param retry 当前重试次数
     */
    private List<DishVO> listByCategory(Long categoryId, int retry) {
        String key = RedisConstants.DISH_LIST_KEY + categoryId;

        // 1. 先查缓存（命中路径完全不加锁）
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached != null) {
            log.info("[缓存命中] key={}", key);
            return unwrap(cached);
        }

        // 2. 未命中：抢重建互斥锁，保证同一分类只有一个线程回源（防击穿）
        RLock rebuildLock = redissonClient.getLock(
                RedisConstants.DISH_CACHE_REBUILD_LOCK + categoryId);
        boolean locked;
        try {
            // 只等待 2 秒，避免请求在锁上无限堆积
            locked = rebuildLock.tryLock(RedisConstants.REBUILD_LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            locked = false;
        } catch (Exception e) {
            // Redis 异常时降级：直接查库，保证接口可用
            log.warn("[重建锁异常] 降级直查数据库, categoryId={}", categoryId, e);
            return queryFromDb(categoryId);
        }

        if (!locked) {
            // 没抢到：最多重试 3 次，仍然抢不到就降级直查数据库
            if (retry >= RedisConstants.LOCK_MAX_RETRY) {
                log.warn("[重建锁等待超限] 降级直查数据库, categoryId={}", categoryId);
                return queryFromDb(categoryId);
            }
            log.info("[未获取重建锁] 等待后重试, categoryId={}, retry={}", categoryId, retry + 1);
            sleep(RedisConstants.LOCK_RETRY_INTERVAL_MS);
            return listByCategory(categoryId, retry + 1);
        }

        try {
            // 3. 读锁：与"更新数据库 + 删缓存"的写锁互斥，防止旧值回填
            RLock readLock = redissonClient
                    .getReadWriteLock(RedisConstants.DISH_CACHE_RW_LOCK)
                    .readLock();
            readLock.lock();
            try {
                // 4. 双重检查：抢锁期间别人可能已经把缓存写好了
                Object again = redisTemplate.opsForValue().get(key);
                if (again != null) {
                    log.info("[缓存命中-二次检查] key={}", key);
                    return unwrap(again);
                }
                return queryFromDbAndCache(categoryId, key);
            } finally {
                unlockQuietly(readLock);
            }
        } finally {
            unlockQuietly(rebuildLock);
        }
    }

    /**
     * 回源并写缓存
     */
    private List<DishVO> queryFromDbAndCache(Long categoryId, String key) {
        List<DishVO> list = queryFromDb(categoryId);

        if (list == null || list.isEmpty()) {
            // 缓存穿透：把空结果也缓存下来，TTL 要短
            redisTemplate.opsForValue().set(key, RedisConstants.EMPTY_CACHE,
                    RedisConstants.EMPTY_CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            log.info("[缓存空值] key={}, ttl={}分钟", key, RedisConstants.EMPTY_CACHE_TTL_MINUTES);
            return Collections.emptyList();
        }

        // 缓存雪崩：TTL 加随机抖动
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

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 释放锁：只释放当前线程持有的锁，避免误删别人的锁；
     * 锁已超时被自动释放时不再抛异常。
     */
    private void unlockQuietly(RLock lock) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            log.warn("[释放锁失败] lock={}", lock.getName(), e);
        }
    }

    // ================== 写路径：写锁保护下 更新DB + 删缓存 ==================

    @Override
    public void executeAndEvictCategory(Long categoryId, Runnable dbOperation) {
        RLock writeLock = redissonClient
                .getReadWriteLock(RedisConstants.DISH_CACHE_RW_LOCK)
                .writeLock();
        writeLock.lock();
        try {
            dbOperation.run();               // 真正的数据库写操作（事务在 Service 层）
            evictCategory(categoryId);       // 再删缓存，属于 Cache Aside
        } finally {
            unlockQuietly(writeLock);
        }
    }

    @Override
    public void executeAndEvictAll(Runnable dbOperation) {
        RLock writeLock = redissonClient
                .getReadWriteLock(RedisConstants.DISH_CACHE_RW_LOCK)
                .writeLock();
        writeLock.lock();
        try {
            dbOperation.run();
            evictAll();
        } finally {
            unlockQuietly(writeLock);
        }
    }

    // ================== 纯缓存操作（内部 + 测试使用） ==================

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
     * 用 SCAN 代替 KEYS，避免大 key 场景下阻塞 Redis
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

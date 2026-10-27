package com.sky;

import com.sky.service.DishCacheService;
import com.sky.service.DishService;
import com.sky.support.TestDataHelper;
import com.sky.vo.DishVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 缓存三件套测试：穿透（空值缓存）、雪崩（随机TTL）、击穿（互斥锁）
 */
@SpringBootTest
@ActiveProfiles("test")
class DishCacheServiceTest {

    private static final long CATEGORY_ID = 11L;
    private static final long NOT_EXIST_CATEGORY_ID = 99999L;
    private static final long TTL_MIN = 120 * 60L;
    private static final long TTL_MAX = 130 * 60L;
    /**
     * 容差：Redis的TTL按秒向上取整，写入后立刻读可能少 1 秒，留几秒余量避免误判
     */
    private static final long TTL_TOLERANCE = 5L;

    @Autowired
    private DishCacheService dishCacheService;

    /**
     * 用 Spy 包一层，统计"真正查数据库"的次数
     */
    @SpyBean
    private DishService dishService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TestDataHelper helper;

    @BeforeEach
    void setUp() {
        helper = new TestDataHelper(jdbcTemplate, stringRedisTemplate);
        helper.clearDishCache();
        clearInvocations(dishService);
    }

    @Test
    void 首次查询应回源数据库并写入带随机TTL的缓存() {
        List<DishVO> list = dishCacheService.listByCategory(CATEGORY_ID);

        assertThat(list).isNotEmpty();
        verify(dishService, times(1)).listWithFlavor(any());
        assertThat(stringRedisTemplate.hasKey("dish_" + CATEGORY_ID)).isTrue();

        Long ttl = stringRedisTemplate.getExpire("dish_" + CATEGORY_ID, TimeUnit.SECONDS);
        assertThat(ttl).as("TTL应为120~130分钟的随机值").isBetween(TTL_MIN - TTL_TOLERANCE, TTL_MAX);
    }

    @Test
    void 二次查询应命中缓存不再查库() {
        List<DishVO> first = dishCacheService.listByCategory(CATEGORY_ID);
        List<DishVO> second = dishCacheService.listByCategory(CATEGORY_ID);

        assertThat(second).hasSameSizeAs(first);
        assertThat(second.get(0).getName()).isEqualTo(first.get(0).getName());
        verify(dishService, times(1)).listWithFlavor(any());
    }

    @Test
    void 查询不存在的分类应缓存空值防止穿透() {
        List<DishVO> list = dishCacheService.listByCategory(NOT_EXIST_CATEGORY_ID);
        assertThat(list).isEmpty();

        String key = "dish_" + NOT_EXIST_CATEGORY_ID;
        // 空值标记不能是Java的null，Redis存不了null，这里存的是字符串"NULL"
        assertThat(stringRedisTemplate.opsForValue().get(key)).contains("NULL");

        Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttl).as("空值缓存的TTL应明显短于正常数据").isBetween(1L, 120L);

        // 再查一次不该再打数据库
        dishCacheService.listByCategory(NOT_EXIST_CATEGORY_ID);
        verify(dishService, times(1)).listWithFlavor(any());
    }

    @Test
    void 多个分类的TTL应随机散开避免雪崩() {
        long[] categories = {11L, 12L, 16L, 17L, 18L};
        Set<Long> ttls = new HashSet<>();

        for (long categoryId : categories) {
            dishCacheService.listByCategory(categoryId);
            Long ttl = stringRedisTemplate.getExpire("dish_" + categoryId, TimeUnit.SECONDS);
            assertThat(ttl).isBetween(TTL_MIN - TTL_TOLERANCE, TTL_MAX);
            ttls.add(ttl);
        }

        assertThat(ttls).as("随机TTL应产生不同的过期时间，否则会同时失效").hasSizeGreaterThan(1);
    }

    @Test
    void 清理分类缓存后对应key应消失() {
        dishCacheService.listByCategory(CATEGORY_ID);
        assertThat(stringRedisTemplate.hasKey("dish_" + CATEGORY_ID)).isTrue();

        dishCacheService.evictCategory(CATEGORY_ID);
        assertThat(stringRedisTemplate.hasKey("dish_" + CATEGORY_ID)).isFalse();
    }

    @Test
    void 清理全部缓存后所有菜品key都应消失() {
        dishCacheService.listByCategory(11L);
        dishCacheService.listByCategory(12L);
        dishCacheService.listByCategory(16L);

        dishCacheService.evictAll();

        Set<String> keys = stringRedisTemplate.keys("dish_*");
        assertThat(keys == null || keys.isEmpty()).isTrue();
    }

    // ================== Redisson 相关 ==================

    @Test
    void 二十个线程并发回源时数据库只被查询一次() throws Exception {
        int threads = 20;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    dishCacheService.listByCategory(CATEGORY_ID);
                } catch (Exception ignored) {
                    // 少量请求降级直查库属于预期
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        assertThat(doneGate.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        verify(dishService, times(1)).listWithFlavor(any());
    }

    @Test
    void 写锁保护的更新会执行数据库操作并清理缓存() {
        dishCacheService.listByCategory(CATEGORY_ID);
        assertThat(stringRedisTemplate.hasKey("dish_" + CATEGORY_ID)).isTrue();

        boolean[] executed = {false};
        dishCacheService.executeAndEvictCategory(CATEGORY_ID, () -> executed[0] = true);

        assertThat(executed[0]).as("数据库操作应被执行").isTrue();
        assertThat(stringRedisTemplate.hasKey("dish_" + CATEGORY_ID)).as("缓存应被清理").isFalse();
    }

    @Test
    void 写锁保护的批量更新会清理全部菜品缓存() {
        dishCacheService.listByCategory(11L);
        dishCacheService.listByCategory(12L);

        dishCacheService.executeAndEvictAll(() -> { });

        Set<String> keys = stringRedisTemplate.keys("dish_*");
        assertThat(keys == null || keys.isEmpty()).isTrue();
    }
}

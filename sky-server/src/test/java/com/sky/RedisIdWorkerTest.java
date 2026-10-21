package com.sky;

import com.sky.component.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局唯一ID生成器测试
 */
@SpringBootTest
@ActiveProfiles("test")
class RedisIdWorkerTest {

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Test
    void 单线程连续生成一万个ID应全部唯一且递增() {
        Set<Long> ids = new HashSet<>();
        long previous = 0;

        for (int i = 0; i < 10000; i++) {
            long id = redisIdWorker.nextId("ut");

            assertThat(ids.add(id)).as("第%d个ID重复了", i + 1).isTrue();
            assertThat(id).as("ID应保持递增").isGreaterThan(previous);
            previous = id;
        }

        assertThat(ids).hasSize(10000);
    }

    @Test
    void 多线程并发生成两千个ID应无重复() throws Exception {
        int threads = 8;
        int perThread = 250;

        Set<Long> ids = Collections.synchronizedSet(new HashSet<>());
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < perThread; i++) {
                        ids.add(redisIdWorker.nextId("ut"));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        assertThat(doneGate.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(ids).as("并发下生成%d个ID", threads * perThread)
                .hasSize(threads * perThread);
    }
}

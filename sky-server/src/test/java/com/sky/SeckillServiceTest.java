package com.sky;

import com.sky.context.BaseContext;
import com.sky.exception.OrderBusinessException;
import com.sky.service.SeckillService;
import com.sky.support.TestDataHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 秒杀业务测试：并发不超卖、一人一单、售罄、跳过支付
 */
@SpringBootTest
@ActiveProfiles("test")
class SeckillServiceTest {

    private static final long SECKILL_ID = 1L;
    private static final int INITIAL_STOCK = 10;

    @Autowired
    private SeckillService seckillService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private TestDataHelper helper;

    @BeforeEach
    void setUp() {
        helper = new TestDataHelper(jdbcTemplate, stringRedisTemplate);
        helper.resetSeckill(SECKILL_ID, INITIAL_STOCK);
    }

    @AfterEach
    void tearDown() {
        //行级清理，避免同一线程复用导致用户串号
        BaseContext.removeCurrentId();
    }

    /**
     * 核心用例：30个不同用户同时抢10件库存
     */
    @Test
    void 三十个并发抢十件库存不能超卖() throws Exception {
        int threads = 30;

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rejectCount = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            long userId = 5000L + i;
            pool.submit(() -> {
                try {
                    //所有线程在这里等发令枪，保证真正同时发起
                    startGate.await();
                    BaseContext.setCurrentId(userId);
                    seckillService.seckill(SECKILL_ID);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    //售罄属于预期内的失败
                    rejectCount.incrementAndGet();
                } finally {
                    BaseContext.removeCurrentId();
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        assertThat(doneGate.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        //秒杀是异步落库的，必须等消费完再断言
        TestDataHelper.awaitUntil("异步建单完成", 15000,
                () -> helper.queryStock(SECKILL_ID) == 0
                        && helper.countSeckillOrders() == INITIAL_STOCK);

        assertThat(successCount.get()).as("拿到抢购资格的数量").isEqualTo(INITIAL_STOCK);
        assertThat(rejectCount.get()).as("被拒绝的数量").isEqualTo(threads - INITIAL_STOCK);

        //三重校验，任何一项不满足都说明超卖了
        assertThat(helper.queryStock(SECKILL_ID)).as("数据库库存必须归零").isZero();
        assertThat(helper.countSeckillOrders()).as("订单数量必须等于初始库存").isEqualTo(INITIAL_STOCK);
        assertThat(helper.queryRedis("seckill:stock:" + SECKILL_ID)).as("Redis库存必须归零").isEqualTo("0");

        Integer duplicate = jdbcTemplate.queryForObject(
                "select count(*) from (select number from orders where remark = '秒杀订单' " +
                        "group by number having count(*) > 1) t", Integer.class);
        assertThat(duplicate).as("订单号不能重复").isZero();
    }

    @Test
    void 同一用户重复抢购应被拒绝() {
        long userId = 6001L;
        BaseContext.setCurrentId(userId);

        String orderNo = seckillService.seckill(SECKILL_ID);
        assertThat(orderNo).isNotBlank();

        assertThatThrownBy(() -> seckillService.seckill(SECKILL_ID))
                .isInstanceOf(OrderBusinessException.class)
                .hasMessageContaining("已抢购过");
    }

    @Test
    void 库存为零时应提示售罄() {
        helper.resetSeckill(SECKILL_ID, 0);
        BaseContext.setCurrentId(7001L);

        assertThatThrownBy(() -> seckillService.seckill(SECKILL_ID))
                .isInstanceOf(OrderBusinessException.class)
                .hasMessageContaining("售罄");
    }

    @Test
    void 活动不存在时应提示() {
        BaseContext.setCurrentId(7002L);

        assertThatThrownBy(() -> seckillService.seckill(99999L))
                .isInstanceOf(OrderBusinessException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void 秒杀订单应跳过支付且金额与明细正确() {
        BaseContext.setCurrentId(8001L);
        String orderNo = seckillService.seckill(SECKILL_ID);

        TestDataHelper.awaitUntil("订单落库", 10000,
                () -> helper.countSeckillOrders() == 1);

        Map<String, Object> order = jdbcTemplate.queryForMap(
                "select id, status, pay_status, amount, remark from orders where number = ?", orderNo);

        assertThat(((Number) order.get("pay_status")).intValue())
                .as("跳过支付：pay_status 应为已支付(1)").isEqualTo(1);
        assertThat(((Number) order.get("status")).intValue())
                .as("状态应为待接单(2)").isEqualTo(2);
        assertThat((BigDecimal) order.get("amount"))
                .as("金额应为秒杀价9.90")
                .isEqualByComparingTo(new BigDecimal("9.90"));

        Integer detailCount = jdbcTemplate.queryForObject(
                "select count(*) from order_detail where order_id = ?",
                Integer.class, order.get("id"));
        assertThat(detailCount).isEqualTo(1);
    }
}

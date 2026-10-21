package com.sky.service.impl;

import com.sky.component.RedisIdWorker;
import com.sky.constant.MessageConstant;
import com.sky.constant.RedisConstants;
import com.sky.context.BaseContext;
import com.sky.dto.SeckillMessage;
import com.sky.entity.SeckillDish;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.SeckillMapper;
import com.sky.service.SeckillOrderHandler;
import com.sky.service.SeckillService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀实现：Redis预减库存 + 阻塞队列异步建单
 * <p>
 * 请求处理链路：
 * 1. Lua脚本原子校验并扣减Redis库存、记录已购用户（挡住绝大部分无效请求）
 * 2. 生成全局唯一订单号后把消息丢进阻塞队列，立即返回"排队中"
 * 3. 后台消费者线程从队列取任务，用乐观锁扣减数据库库存并建单
 */
@Service
@Slf4j
public class SeckillServiceImpl implements SeckillService {

    /**
     * 预减库存脚本，类加载时读取一次
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("lua/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 阻塞队列：线程安全，满了以后offer返回false，天然起到限流作用
     */
    private final BlockingQueue<SeckillMessage> queue =
            new LinkedBlockingQueue<>(RedisConstants.SECKILL_QUEUE_CAPACITY);

    /**
     * 消费者线程池
     */
    private final ExecutorService consumerPool =
            Executors.newFixedThreadPool(RedisConstants.SECKILL_CONSUMER_COUNT);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Autowired
    private SeckillMapper seckillMapper;

    @Autowired
    private SeckillOrderHandler seckillOrderHandler;

    /**
     * 应用启动：把数据库库存预热到Redis，然后拉起消费者线程
     */
    @PostConstruct
    public void init() {
        preheatStock();
        for (int i = 0; i < RedisConstants.SECKILL_CONSUMER_COUNT; i++) {
            consumerPool.submit(this::consumeLoop);
        }
        log.info("[秒杀] 消费者线程启动完成，数量={}", RedisConstants.SECKILL_CONSUMER_COUNT);
    }

    @PreDestroy
    public void destroy() {
        consumerPool.shutdownNow();
        log.info("[秒杀] 消费者线程已停止，队列剩余={}", queue.size());
    }

    /**
     * 库存预热：以数据库为准刷到Redis
     */
    private void preheatStock() {
        List<SeckillDish> list = seckillMapper.listActive();
        for (SeckillDish seckillDish : list) {
            String stockKey = RedisConstants.SECKILL_STOCK_KEY + seckillDish.getId();
            stringRedisTemplate.opsForValue()
                    .set(stockKey, String.valueOf(seckillDish.getStock()));
            log.info("[秒杀预热] id={}, name={}, stock={}",
                    seckillDish.getId(), seckillDish.getName(), seckillDish.getStock());
        }
    }

    @Override
    public List<SeckillDish> list() {
        return seckillMapper.listActive();
    }

    @Override
    public String seckill(Long seckillId) {
        Long userId = BaseContext.getCurrentId();

        //1. Lua脚本原子执行：判断库存 + 判断是否重复下单 + 扣库存 + 记录用户
        String stockKey = RedisConstants.SECKILL_STOCK_KEY + seckillId;
        String buyersKey = RedisConstants.SECKILL_BUYERS_KEY + seckillId;
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Arrays.asList(stockKey, buyersKey),
                userId.toString());

        if (result == null) {
            throw new OrderBusinessException(MessageConstant.SECKILL_BUSY);
        }
        if (result == -1L) {
            throw new OrderBusinessException(MessageConstant.SECKILL_NOT_FOUND);
        }
        if (result == 0L) {
            throw new OrderBusinessException(MessageConstant.SECKILL_SOLD_OUT);
        }
        if (result == 2L) {
            throw new OrderBusinessException(MessageConstant.SECKILL_REPEAT);
        }

        //2. 抢购资格已拿到，生成全局唯一订单号
        String orderNo = String.valueOf(redisIdWorker.nextId(RedisConstants.ID_PREFIX_SECKILL));
        stringRedisTemplate.opsForValue()
                .set(RedisConstants.SECKILL_RESULT_KEY + orderNo, "0", 1, TimeUnit.DAYS);

        //3. 入队，立即返回。真正写库由消费者异步完成
        boolean offered = queue.offer(SeckillMessage.builder()
                .userId(userId)
                .seckillId(seckillId)
                .orderNo(orderNo)
                .build());

        if (!offered) {
            //队列满了，回滚Redis里的库存和用户记录
            rollback(seckillId, userId, orderNo);
            throw new OrderBusinessException(MessageConstant.SECKILL_BUSY);
        }

        log.info("[秒杀入队成功] userId={}, seckillId={}, orderNo={}, queueSize={}",
                userId, seckillId, orderNo, queue.size());
        return orderNo;
    }

    @Override
    public Integer queryResult(String orderNo) {
        String value = stringRedisTemplate.opsForValue()
                .get(RedisConstants.SECKILL_RESULT_KEY + orderNo);
        return value == null ? -1 : Integer.valueOf(value);
    }

    /**
     * 消费者死循环：从队列取消息建单
     */
    private void consumeLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            SeckillMessage message = null;
            try {
                message = queue.take();
                seckillOrderHandler.createOrder(message);
                //建单成功
                stringRedisTemplate.opsForValue()
                        .set(RedisConstants.SECKILL_RESULT_KEY + message.getOrderNo(), "1",
                                1, TimeUnit.DAYS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[秒杀建单失败] orderNo={}", message == null ? null : message.getOrderNo(), e);
                if (message != null) {
                    rollback(message.getSeckillId(), message.getUserId(), message.getOrderNo());
                }
            }
        }
    }

    /**
     * 回滚Redis中的库存和购买记录
     */
    private void rollback(Long seckillId, Long userId, String orderNo) {
        stringRedisTemplate.opsForValue()
                .increment(RedisConstants.SECKILL_STOCK_KEY + seckillId);
        stringRedisTemplate.opsForSet()
                .remove(RedisConstants.SECKILL_BUYERS_KEY + seckillId, userId.toString());
        stringRedisTemplate.opsForValue()
                .set(RedisConstants.SECKILL_RESULT_KEY + orderNo, "2", 1, TimeUnit.DAYS);
        log.warn("[秒杀回滚] seckillId={}, userId={}, orderNo={}", seckillId, userId, orderNo);
    }
}

package com.sky.component;

import com.sky.constant.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Redis 全局唯一ID生成器
 * <p>
 * 结构：符号位(1) + 时间戳(32位，单位秒) + 自增序列(32位)
 * 高32位取当前时间与起始时间的差值，保证整体递增；
 * 低32位取Redis自增，保证同一秒内的并发请求不重复。
 */
@Component
@Slf4j
public class RedisIdWorker {

    /**
     * 起始时间戳：2022-01-01 00:00:00（北京时间）
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;

    /**
     * 自增序列的位移量
     */
    private static final int COUNT_BITS = 32;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 生成全局唯一ID
     *
     * @param keyPrefix 业务前缀，如 order、seckill
     */
    public long nextId(String keyPrefix) {
        //1. 时间戳部分
        LocalDateTime now = LocalDateTime.now();
        long timestamp = now.toEpochSecond(ZoneOffset.ofHours(8)) - BEGIN_TIMESTAMP;

        //2. 序列号部分：按天分key，避免单个key无限增长
        String date = now.format(DATE_FORMATTER);
        String key = RedisConstants.ID_KEY_PREFIX + keyPrefix + ":" + date;
        Long count = stringRedisTemplate.opsForValue().increment(key);
        //自增key保留一天即可
        stringRedisTemplate.expire(key, Duration.ofDays(1));

        //3. 拼接：时间戳左移32位，低位放自增序列
        return (timestamp << COUNT_BITS) | (count & 0xFFFFFFFFL);
    }
}

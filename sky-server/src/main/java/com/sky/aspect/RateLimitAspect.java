package com.sky.aspect;

import com.sky.annotation.RateLimit;
import com.sky.constant.RedisConstants;
import com.sky.context.BaseContext;
import com.sky.enumeration.RateLimitType;
import com.sky.exception.RateLimitException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.UUID;

/**
 * 接口限流切面
 * <p>
 * 用 Redis + Lua 实现滑动窗口：Lua脚本内一次性完成"清理过期记录 → 统计 → 判断 → 记录"，
 * 因为Redis执行脚本是单线程原子的，所以并发下不会出现"都判断通过然后一起放行"的超发问题。
 * <p>
 * 不用固定窗口(INCR + EXPIRE)的原因：固定窗口在两个窗口交界处可能瞬间放过 2 倍流量；
 * 滑动窗口按真实时间轴统计，边界更平滑。
 */
@Aspect
@Component
@Slf4j
public class RateLimitAspect {

    private static final DefaultRedisScript<Long> RATE_LIMIT_SCRIPT;

    static {
        RATE_LIMIT_SCRIPT = new DefaultRedisScript<>();
        RATE_LIMIT_SCRIPT.setLocation(new ClassPathResource("lua/rate_limit.lua"));
        RATE_LIMIT_SCRIPT.setResultType(Long.class);
    }

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String key = buildKey(joinPoint, rateLimit);

        long waitMillis = acquire(key, rateLimit.limit(), rateLimit.windowSeconds());
        if (waitMillis > 0) {
            log.warn("[限流] key={}, 阈值={}次/{}秒, 建议等待{}ms",
                    key, rateLimit.limit(), rateLimit.windowSeconds(), waitMillis);
            throw new RateLimitException(
                    String.format("请求过于频繁，请 %.1f 秒后重试", waitMillis / 1000.0));
        }

        return joinPoint.proceed();
    }

    /**
     * @return 0 表示放行；大于0表示被限流，值为建议等待的毫秒数
     */
    private long acquire(String key, int limit, int windowSeconds) {
        long now = System.currentTimeMillis();
        Long result = stringRedisTemplate.execute(
                RATE_LIMIT_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(now),
                String.valueOf(windowSeconds * 1000L),
                String.valueOf(limit),
                UUID.randomUUID().toString());
        return result == null ? 0L : result;
    }

    private String buildKey(ProceedingJoinPoint joinPoint, RateLimit rateLimit) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String methodName = rateLimit.prefix().isEmpty()
                ? signature.getMethod().getName()
                : rateLimit.prefix();
        return RedisConstants.RATE_LIMIT_KEY + methodName + ":" + identity(rateLimit.type());
    }

    private String identity(RateLimitType type) {
        switch (type) {
            case IP:
                return clientIp();
            case GLOBAL:
                return "all";
            case USER:
            default:
                Long userId = BaseContext.getCurrentId();
                return userId == null ? "anonymous:" + clientIp() : String.valueOf(userId);
        }
    }

    /**
     * 取客户端IP，兼容Nginx反向代理
     */
    private String clientIp() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }
        HttpServletRequest request = attributes.getRequest();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty() && !"unknown".equalsIgnoreCase(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

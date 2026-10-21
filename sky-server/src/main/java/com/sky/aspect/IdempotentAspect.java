package com.sky.aspect;

import com.sky.annotation.Idempotent;
import com.sky.constant.MessageConstant;
import com.sky.constant.RedisConstants;
import com.sky.context.BaseContext;
import com.sky.exception.IdempotentException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.util.concurrent.TimeUnit;

/**
 * 接口幂等切面（Token 机制）
 * <p>
 * 核心是 Redis 的 DEL 命令：它本身就是原子操作，
 * 同一个 token 并发提交时，只有一个线程能删成功（返回true），其余线程拿到false → 判定为重复提交。
 */
@Aspect
@Component
@Slf4j
public class IdempotentAspect {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        String token = currentToken();
        if (token == null || token.trim().isEmpty()) {
            throw new IdempotentException(MessageConstant.IDEMPOTENT_TOKEN_MISSING);
        }

        Long userId = BaseContext.getCurrentId();
        String key = RedisConstants.IDEMPOTENT_TOKEN_KEY + userId + ":" + token;

        //原子地"取走"token：删掉了才说明这是第一次提交
        Boolean consumed = stringRedisTemplate.delete(key);
        if (!Boolean.TRUE.equals(consumed)) {
            log.warn("[幂等拦截] 重复提交被拒绝, userId={}, token={}", userId, token);
            throw new IdempotentException(idempotent.message());
        }

        try {
            return joinPoint.proceed();
        } catch (Throwable e) {
            //业务处理失败：把token还回去，允许前端用同一个token重试
            //（能走到这里说明事务已回滚，不会产生半成品数据）
            stringRedisTemplate.opsForValue()
                    .set(key, "1", idempotent.expireSeconds(), TimeUnit.SECONDS);
            log.warn("[幂等回补] 业务失败，token已恢复, userId={}, 原因={}", userId, e.getMessage());
            throw e;
        }
    }

    private String currentToken() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        return request.getHeader(RedisConstants.IDEMPOTENT_TOKEN_HEADER);
    }
}

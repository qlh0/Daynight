package com.sky.annotation;

import com.sky.enumeration.RateLimitType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解
 * <p>
 * 基于 Redis + Lua 的滑动窗口实现：同一身份在 windowSeconds 秒内最多允许 limit 次请求。
 * 用法：加在Controller方法上即可，由 RateLimitAspect 统一拦截。
 *
 * <pre>
 * &#64;RateLimit(limit = 5, windowSeconds = 1)                 // 每个用户每秒最多5次
 * &#64;RateLimit(limit = 100, windowSeconds = 1, type = RateLimitType.GLOBAL)  // 全局限流
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * 窗口内允许的最大请求数
     */
    int limit() default 10;

    /**
     * 窗口长度（秒）
     */
    int windowSeconds() default 1;

    /**
     * 限流维度：按用户 / 按IP / 全局
     */
    RateLimitType type() default RateLimitType.USER;

    /**
     * 自定义key前缀，默认取方法名
     */
    String prefix() default "";
}

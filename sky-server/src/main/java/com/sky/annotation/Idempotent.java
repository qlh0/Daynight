package com.sky.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口幂等注解（Token 机制）
 * <p>
 * 使用流程：
 * 1. 客户端先调 GET /user/idempotent/token 拿到一次性token
 * 2. 提交请求时把token放到请求头 idempotent-token 中
 * 3. 服务端用 Redis 的 DEL 原子地"取走"token，取到了才执行，取不到说明重复提交
 *
 * <pre>
 * &#64;Idempotent                                  // 默认提示"请勿重复提交"
 * &#64;Idempotent(message = "订单正在处理中")      // 自定义提示
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /**
     * token有效期（秒），用于业务失败后把token还回去
     */
    long expireSeconds() default 300;

    /**
     * 重复提交时的提示语
     */
    String message() default "请勿重复提交";
}

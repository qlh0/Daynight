package com.sky.enumeration;

/**
 * 限流的身份维度
 */
public enum RateLimitType {

    /**
     * 按用户限流（需要登录，从ThreadLocal取userId）
     */
    USER,

    /**
     * 按客户端IP限流（适合未登录接口）
     */
    IP,

    /**
     * 全局限流（所有请求共用一个计数器，用于保护下游）
     */
    GLOBAL
}

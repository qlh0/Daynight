package com.sky.exception;

/**
 * 限流异常：请求超过允许的频率
 */
public class RateLimitException extends BaseException {

    public RateLimitException(String msg) {
        super(msg);
    }
}

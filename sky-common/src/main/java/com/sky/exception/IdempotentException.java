package com.sky.exception;

/**
 * 幂等异常：重复提交或缺失幂等token
 */
public class IdempotentException extends BaseException {

    public IdempotentException(String msg) {
        super(msg);
    }
}

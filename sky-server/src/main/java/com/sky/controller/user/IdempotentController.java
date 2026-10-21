package com.sky.controller.user;

import com.sky.constant.RedisConstants;
import com.sky.context.BaseContext;
import com.sky.result.Result;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 幂等token接口
 * <p>
 * 客户端提交订单前先取token，提交时放到请求头 idempotent-token 中。
 * token 与 userId 绑定，不能跨用户使用。
 */
@RestController("userIdempotentController")
@RequestMapping("/user/idempotent")
@Api(tags = "用户端-幂等token接口")
@Slf4j
public class IdempotentController {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @GetMapping("/token")
    @ApiOperation("获取一次性幂等token（提交订单时放在请求头idempotent-token中）")
    public Result<String> token() {
        Long userId = BaseContext.getCurrentId();
        String token = UUID.randomUUID().toString().replace("-", "");

        stringRedisTemplate.opsForValue().set(
                RedisConstants.IDEMPOTENT_TOKEN_KEY + userId + ":" + token,
                "1",
                RedisConstants.IDEMPOTENT_TOKEN_TTL_SECONDS,
                TimeUnit.SECONDS);

        log.info("[幂等token] 发放 userId={}, token={}", userId, token);
        return Result.success(token);
    }
}

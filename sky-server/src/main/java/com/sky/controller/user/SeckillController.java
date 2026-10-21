package com.sky.controller.user;

import com.sky.annotation.RateLimit;
import com.sky.entity.SeckillDish;
import com.sky.enumeration.RateLimitType;
import com.sky.result.Result;
import com.sky.service.SeckillService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户端秒杀接口
 * <p>
 * 路径在 /user/** 下，会被 JwtTokenUserInterceptor 拦截，
 * 拦截器解析token后把userId放进 BaseContext，秒杀下单要用。
 */
@RestController("userSeckillController")
@RequestMapping("/user/seckill")
@Api(tags = "用户端-秒杀接口")
@Slf4j
public class SeckillController {

    @Autowired
    private SeckillService seckillService;

    @GetMapping("/list")
    @ApiOperation("秒杀活动列表")
    public Result<List<SeckillDish>> list() {
        return Result.success(seckillService.list());
    }

    @PostMapping("/{seckillId}")
    @ApiOperation("参与秒杀，返回订单号（异步建单）")
    @RateLimit(limit = 5, windowSeconds = 1, type = RateLimitType.USER)
    public Result<String> seckill(@PathVariable Long seckillId) {
        log.info("参与秒杀：seckillId={}", seckillId);
        return Result.success(seckillService.seckill(seckillId));
    }

    @GetMapping("/result/{orderNo}")
    @ApiOperation("查询秒杀结果：0排队中 1成功 2失败")
    public Result<Integer> result(@PathVariable String orderNo) {
        return Result.success(seckillService.queryResult(orderNo));
    }
}

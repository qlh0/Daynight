package com.sky.controller.admin;

import com.sky.result.Result;
import com.sky.service.SeckillService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 秒杀运维接口
 * <p>
 * 说明：这里的模式切换只用于「同步下单 vs 阻塞队列异步下单」的对比测试，
 * 让两种模式跑在同一个 JVM 里，排除重启和冷启动带来的差异。
 */
@RestController("adminSeckillController")
@RequestMapping("/admin/seckill")
@Api(tags = "管理端-秒杀运维接口")
@Slf4j
public class SeckillAdminController {

    @Autowired
    private SeckillService seckillService;

    /**
     * 切换下单模式
     *
     * @param async true=阻塞队列异步（默认）；false=请求线程内同步建单
     */
    @PostMapping("/mode")
    @ApiOperation("切换秒杀下单模式")
    public Result<String> switchMode(@RequestParam("async") boolean async) {
        seckillService.setAsyncEnabled(async);
        log.warn("[秒杀模式切换] asyncEnabled={}", async);
        return Result.success(async ? "ASYNC" : "SYNC");
    }

    /**
     * 查询当前模式
     */
    @GetMapping("/mode")
    @ApiOperation("查询秒杀下单模式")
    public Result<String> currentMode() {
        return Result.success(seckillService.isAsyncEnabled() ? "ASYNC" : "SYNC");
    }
}

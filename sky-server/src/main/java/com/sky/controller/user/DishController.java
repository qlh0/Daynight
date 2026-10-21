package com.sky.controller.user;

import com.sky.result.Result;
import com.sky.service.DishCacheService;
import com.sky.vo.DishVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController("userDishController")
@RequestMapping("/user/dish")
@Slf4j
@Api(tags = "C端-菜品浏览接口")
public class DishController {

    @Autowired
    private DishCacheService dishCacheService;

    /**
     * 根据分类id查询菜品
     * <p>
     * 缓存策略统一放在 DishCacheService 里：空值缓存防穿透、随机TTL防雪崩、互斥锁防击穿
     *
     * @param categoryId
     * @return
     */
    @GetMapping("/list")
    @ApiOperation("根据分类id查询菜品")
    public Result<List<DishVO>> list(Long categoryId) {
        return Result.success(dishCacheService.listByCategory(categoryId));
    }

}

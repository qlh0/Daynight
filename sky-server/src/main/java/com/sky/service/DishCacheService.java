package com.sky.service;

import com.sky.vo.DishVO;

import java.util.List;

/**
 * 菜品缓存服务：统一处理缓存穿透、雪崩、击穿
 */
public interface DishCacheService {

    /**
     * 根据分类id查询菜品，优先走缓存
     */
    List<DishVO> listByCategory(Long categoryId);

    /**
     * 清除某个分类的缓存
     */
    void evictCategory(Long categoryId);

    /**
     * 清除全部菜品分类缓存
     */
    void evictAll();
}

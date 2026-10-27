package com.sky.service;

import com.sky.vo.DishVO;

import java.util.List;

/**
 * 菜品缓存服务：统一处理缓存穿透、雪崩、击穿，以及缓存与数据库的一致性
 */
public interface DishCacheService {

    /**
     * 根据分类id查询菜品，优先走缓存
     */
    List<DishVO> listByCategory(Long categoryId);

    /**
     * 在写锁保护下执行数据库写操作，然后清理该分类的缓存
     * <p>
     * 写锁与回源重建时的读锁互斥，保证"更新数据库 + 删缓存"不会被"读旧值回填缓存"打断。
     *
     * @param categoryId   受影响的分类id
     * @param dbOperation  真正的数据库写操作（通常是 dishService 的某个方法，内部自带事务）
     */
    void executeAndEvictCategory(Long categoryId, Runnable dbOperation);

    /**
     * 在写锁保护下执行数据库写操作，然后清理全部菜品缓存
     * （修改/批量删除可能跨分类，所以整体清理）
     */
    void executeAndEvictAll(Runnable dbOperation);

    /**
     * 只清缓存，不加锁（供内部与测试使用）
     */
    void evictCategory(Long categoryId);

    /**
     * 只清全部菜品缓存，不加锁（供内部与测试使用）
     */
    void evictAll();
}

package com.sky.service;

import com.sky.entity.SeckillDish;

import java.util.List;

/**
 * 秒杀业务
 */
public interface SeckillService {

    /**
     * 秒杀活动列表
     */
    List<SeckillDish> list();

    /**
     * 参与秒杀
     *
     * @param seckillId 秒杀活动id
     * @return 订单号（异步下单，此时只是"排队中"）
     */
    String seckill(Long seckillId);

    /**
     * 查询异步下单结果
     *
     * @return 0排队中 1成功 2失败 -1无记录
     */
    Integer queryResult(String orderNo);

    /**
     * 切换下单模式（用于对比测试）
     *
     * @param asyncEnabled true=阻塞队列异步下单；false=请求线程内同步建单
     */
    void setAsyncEnabled(boolean asyncEnabled);

    /**
     * 当前下单模式
     */
    boolean isAsyncEnabled();
}

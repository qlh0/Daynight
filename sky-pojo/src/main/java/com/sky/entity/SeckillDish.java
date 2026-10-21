package com.sky.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀菜品
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillDish implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    //关联的菜品id
    private Long dishId;

    //秒杀名称（冗余，避免每次关联查询）
    private String name;

    //图片
    private String image;

    //秒杀价
    private BigDecimal seckillPrice;

    //秒杀库存，乐观锁扣减的字段
    private Integer stock;

    //活动开始时间
    private LocalDateTime beginTime;

    //活动结束时间
    private LocalDateTime endTime;

    //1上架 0下架
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    //原价，来自dish表，仅用于展示
    private BigDecimal originalPrice;
}

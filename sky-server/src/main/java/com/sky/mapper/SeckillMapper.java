package com.sky.mapper;

import com.sky.entity.SeckillDish;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SeckillMapper {

    /**
     * 查询进行中的秒杀活动
     */
    List<SeckillDish> listActive();

    /**
     * 根据id查询秒杀活动
     */
    SeckillDish getById(@Param("id") Long id);

    /**
     * 扣减库存（乐观锁：where stock > 0）
     *
     * @return 影响行数，0表示库存已空，扣减失败
     */
    int deductStock(@Param("id") Long id);
}

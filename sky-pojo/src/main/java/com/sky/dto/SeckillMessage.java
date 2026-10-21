package com.sky.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 秒杀消息，放入阻塞队列后由异步线程消费建单
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    //下单用户id
    private Long userId;

    //秒杀活动id
    private Long seckillId;

    //订单号（由Redis全局唯一ID生成）
    private String orderNo;
}

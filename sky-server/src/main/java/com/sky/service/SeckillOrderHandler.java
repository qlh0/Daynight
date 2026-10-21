package com.sky.service;

import com.sky.constant.MessageConstant;
import com.sky.dto.SeckillMessage;
import com.sky.entity.AddressBook;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.entity.SeckillDish;
import com.sky.exception.OrderBusinessException;
import com.sky.mapper.AddressBookMapper;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.SeckillMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 秒杀订单落库
 * <p>
 * 单独抽成一个bean：异步消费者调用它的 @Transactional 方法，
 * 如果和消费者写在同一个类里，属于this调用，事务注解会失效。
 */
@Service
@Slf4j
public class SeckillOrderHandler {

    @Autowired
    private SeckillMapper seckillMapper;
    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private AddressBookMapper addressBookMapper;

    /**
     * 创建秒杀订单（跳过支付，直接进入待接单）
     */
    @Transactional(rollbackFor = Exception.class)
    public void createOrder(SeckillMessage message) {
        //1. 乐观锁扣减库存，影响行数为0说明已经卖完
        int rows = seckillMapper.deductStock(message.getSeckillId());
        if (rows == 0) {
            log.warn("[乐观锁扣减失败] 库存不足, seckillId={}, orderNo={}",
                    message.getSeckillId(), message.getOrderNo());
            throw new OrderBusinessException(MessageConstant.SECKILL_STOCK_ERROR);
        }

        SeckillDish seckillDish = seckillMapper.getById(message.getSeckillId());
        if (seckillDish == null) {
            throw new OrderBusinessException(MessageConstant.SECKILL_NOT_FOUND);
        }

        //2. 取用户默认地址，没有的话给个占位
        AddressBook addressBook = null;
        List<AddressBook> defaults = addressBookMapper.list(AddressBook.builder()
                .userId(message.getUserId())
                .isDefault(1)
                .build());
        if (defaults != null && !defaults.isEmpty()) {
            addressBook = defaults.get(0);
        }

        //3. 写订单。秒杀跳过支付：payStatus=已支付，status=待接单
        Orders order = Orders.builder()
                .number(message.getOrderNo())
                .status(Orders.TO_BE_CONFIRMED)
                .userId(message.getUserId())
                .addressBookId(addressBook == null ? 0L : addressBook.getId())
                .orderTime(LocalDateTime.now())
                .checkoutTime(LocalDateTime.now())
                .payMethod(1)
                .payStatus(Orders.PAID)
                .amount(seckillDish.getSeckillPrice())
                .remark("秒杀订单")
                .phone(addressBook == null ? null : addressBook.getPhone())
                .address(addressBook == null ? null : addressBook.getDetail())
                .consignee(addressBook == null ? "秒杀用户" : addressBook.getConsignee())
                .deliveryStatus(1)
                .packAmount(0)
                .tablewareNumber(0)
                .tablewareStatus(1)
                .build();
        orderMapper.insert(order);

        //4. 写订单明细
        OrderDetail detail = OrderDetail.builder()
                .name(seckillDish.getName())
                .image(seckillDish.getImage())
                .orderId(order.getId())
                .dishId(seckillDish.getDishId())
                .number(1)
                .amount(seckillDish.getSeckillPrice())
                .build();
        orderDetailMapper.insertBatch(Collections.singletonList(detail));

        log.info("[秒杀建单完成] orderId={}, orderNo={}, userId={}, dish={}",
                order.getId(), message.getOrderNo(), message.getUserId(), seckillDish.getName());
    }
}

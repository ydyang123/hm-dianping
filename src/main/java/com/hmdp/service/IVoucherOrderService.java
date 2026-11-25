package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {
    //接收秒杀请求并发送MQ消息
    Result seckillVoucher(Long voucherId);
    //消费消息、加用户锁
    void handleVoucherOrder(VoucherOrder voucherOrder);
    //使用事务写MySQL
    void createVoucherOrder(VoucherOrder voucherOrder);
    //查询订单状态
    Result queryOrderStatus(Long orderId);
}

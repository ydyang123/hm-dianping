package com.hmdp.mq;

public class RabbitMqConstants {
    //优惠券订单交换机
    public static final String VOUCHER_ORDER_EXCHANGE = "hmdp.voucher.order.direct";

    //优惠券订单队列
    public static final String VOUCHER_ORDER_QUEUE = "hmdp.voucher.order.queue";

    //优惠券订单路由键
    public static final String VOUCHER_ORDER_ROUTING_KEY = "voucher.order.create";

    //优惠券订单outbox key
    public static final String VOUCHER_ORDER_OUTBOX_KEY = "seckill:order:outbox";

    //私有构造函数，防止实例化
    private RabbitMqConstants(){

    }
}

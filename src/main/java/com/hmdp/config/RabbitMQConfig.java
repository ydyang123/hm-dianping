package com.hmdp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.mq.RabbitMqConstants;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    //创建持久化的优惠券订单交换机
    @Bean
    public DirectExchange voucherOrderExchange() {
        return ExchangeBuilder
                .directExchange(RabbitMqConstants.VOUCHER_ORDER_EXCHANGE)
                .durable(true)
                .build();
    }
    //创建持久化的优惠券订单队列
    @Bean
    public Queue voucherOrderQueue() {
        return QueueBuilder
                .durable(RabbitMqConstants.VOUCHER_ORDER_QUEUE)
                .build();
    }
    //将订单队列绑定到订单交换机
    @Bean
    public Binding voucherOrderBinding(Queue voucherOrderQueue, DirectExchange voucherOrderExchange) {
        return BindingBuilder
                .bind(voucherOrderQueue)
                .to(voucherOrderExchange)
                .with(RabbitMqConstants.VOUCHER_ORDER_ROUTING_KEY);
    }
    //使用 JSON 传递 Java 对象，不使用 Java 原生序列化，目的是为了方便开发和使用
    @Bean
    public MessageConverter rabbitMessageConverter(
            ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    //优惠券订单专用的手动ACK监听器工厂。
    @Bean
    public SimpleRabbitListenerContainerFactory manualAckListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory) {
        // 创建一个监听器工厂
        SimpleRabbitListenerContainerFactory factory =
                new SimpleRabbitListenerContainerFactory();

        // 继承application.yaml中的RabbitMQ连接及消息转换配置
        configurer.configure(factory, connectionFactory);

        // 改为手动ACK
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);

        // 每次最多预取一条，处理完成后再接收下一条
        factory.setPrefetchCount(1);

        return factory;
    }

    //死信队列配置
    @Bean
    public Declarables voucherOrderDeadLetterTopology() {
        // 创建死信交换机
        DirectExchange exchange = ExchangeBuilder
                .directExchange("hmdp.voucher.order.dlx")
                .durable(true)
                .build();

        // 创建死信队列
        Queue queue = QueueBuilder
                .durable("hmdp.voucher.order.dlq")
                .build();

        // 绑定死信队列到死信交换机
        Binding binding = BindingBuilder.bind(queue)
                .to(exchange)
                .with("voucher.order.dead");

        return new Declarables(exchange, queue, binding);
    }
}

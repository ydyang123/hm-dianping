package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    public VoucherOrderController(IVoucherOrderService iVoucherOrderService) {
        this.voucherOrderService = voucherOrderService;
    }

    /**
     * 秒杀优惠券下单
     * @param voucherId
     * @return
     */
    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        //
        return voucherOrderService.seckillVoucher(voucherId);
    }

    /**
     * 查询订单状态
     * @param orderId
     * @return
     */
    @GetMapping("/status/{orderId}")
    public Result queryOrderStatus(@PathVariable("orderId") Long orderId) {
        return voucherOrderService.queryOrderStatus(orderId);
    }
}

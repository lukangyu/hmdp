package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {


    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    /**
     * 秒杀优惠券
     * @param voucherId 优惠券id
     * @return 订单id
     */
    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        // 1. 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        //2.判断优惠券是否在售
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("优惠券尚未开始");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("优惠券已过期");
        }

        //3.库存是否充足
        Integer stock = voucher.getStock();
        if (stock < 1) {
            return Result.fail("库存不足");
        }
        return createVoucherOrder(voucherId);
    }

    private synchronized Result createVoucherOrder(Long voucherId) {
        //4.判断用户是否已经购买过
        Long useId = UserHolder.getUser().getId();
        if (query().eq("user_id", useId).eq("voucher_id", voucherId).count() > 0) {
            return Result.fail("用户已经购买过");
        }
        //5.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("库存不足");
        }
        //6.创建订单

        VoucherOrder voucherOrder = new VoucherOrder();
        //6.1.生成订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //6.2.设置用户id
        voucherOrder.setUserId(useId);
        //6.3.设置代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        //7.返回订单id
        return Result.ok(orderId);
    }
}

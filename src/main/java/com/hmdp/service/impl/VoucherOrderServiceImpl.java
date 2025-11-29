package com.hmdp.service.impl;


import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {


    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;


    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new org.springframework.core.io.ClassPathResource("lua/seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }


    //线程池，创建一个单线程的线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //线程启动，保证线程在类初始化后立刻完成
    @PostConstruct
    private void init() {
        //初始化redis
        clearSeckillData();
        // 初始化流和消费者组
        initStreamAndConsumerGroup();
        // 预热库存数据
        warmupSeckillStock();
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private void initStreamAndConsumerGroup() {
        String queueName = "stream.orders";
        try {
            // 先尝试创建流和消费者组
            stringRedisTemplate.opsForStream().add(queueName, Collections.singletonMap("type", "init"));
            log.debug("Created stream: {}", queueName);
        } catch (Exception e) {
            log.debug("Stream {} may already exist", queueName);
        }

        try {
            // 再尝试创建消费者组
            stringRedisTemplate.opsForStream().createGroup(queueName, ReadOffset.from("0"), "g1");
            log.debug("Created consumer group g1 for stream: {}", queueName);
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debug("Consumer group g1 already exists for stream: {}", queueName);
            } else {
                log.warn("Error creating consumer group for stream {}: {}", queueName, e.getMessage());
            }
        }
    }
    /**
     * 预热秒杀库存数据到Redis
     */
    private void warmupSeckillStock() {
        try {
            // 查询所有秒杀券
            List<SeckillVoucher> seckillVouchers = seckillVoucherService.list();

            for (SeckillVoucher seckillVoucher : seckillVouchers) {
                Long voucherId = seckillVoucher.getVoucherId();
                Integer stock = seckillVoucher.getStock();

                // 将库存数据存入Redis
                String stockKey = "seckill:stock:" + voucherId;
                stringRedisTemplate.opsForValue().set(stockKey, stock.toString());

                log.info("预热库存数据: voucherId={}, stock={}", voucherId, stock);
            }

            log.info("Redis库存数据预热完成，共预热 {} 个秒杀券", seckillVouchers.size());
        } catch (Exception e) {
            log.error("预热Redis库存数据时发生错误", e);
        }
    }

    /**
     * 清理Redis中的秒杀数据，这是由于在lua脚本中，库存数据被修改，导致Redis中的库存数据与数据库中的不一致，
     * 所以需要清理Redis中的库存数据
     */
    private void clearSeckillData() {
        try {
            // 删除所有库存键
            Set<String> keys = stringRedisTemplate.keys("seckill:stock:*");
            if (keys != null) {
                stringRedisTemplate.delete(keys);
            }

            // 删除所有用户订单键
            keys = stringRedisTemplate.keys("seckill:order:*");
            if (keys != null) {
                stringRedisTemplate.delete(keys);
            }

            // 清空Redis Stream
            stringRedisTemplate.opsForStream().trim("stream.orders", 0);

            log.info("Redis秒杀相关数据已清理完成");
        } catch (Exception e) {
            log.error("清理Redis秒杀数据失败", e);
        }
    }

    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    //1.获取队列中的订单信息，XREADGROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );

                    //2.判断是否成功

                    //2.1.如果获取失败，说明没有订单，继续获取
                    if(list == null || list.isEmpty()) {
                        continue;
                    }

                    //解析订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //3.如果获取成功，说明有订单，进行下单
                    handleVoucherOrder(voucherOrder);
                    //4.ACK确认 SACK stream.order id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("订单异常", e);
                    handlePendingList();
                }
            }
        }
        private void handlePendingList() {
            while (true) {
                try {
                    //1.获取pendingList中的订单信息，XREADGROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );

                    //2.判断是否成功

                    //2.1.如果获取失败，说明没有pendingList订单，结束获取
                    if(list == null || list.isEmpty()) {
                        break;
                    }

                    //解析订单信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //3.如果获取成功，说明有订单，进行下单
                    handleVoucherOrder(voucherOrder);
                    //4.ACK确认 SACK stream.order id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        log.error("线程休眠异常", ex);
                    }
                }
            }
        }

        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            Long userId = voucherOrder.getUserId();
            //创建锁，兜底
            RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + userId);
            try {
                // 增加超时时间，避免死锁
                boolean isLock = lock.tryLock(5, 10, TimeUnit.SECONDS);
                if(!isLock){
                    log.error("获取锁失败，用户ID: {}", userId);
                    return;
                }
                proxy.createVoucherOrder(voucherOrder);
            } catch (InterruptedException e) {
                log.error("获取锁被中断，用户ID: {}", userId, e);
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("处理订单异常，用户ID: {}", userId, e);
            } finally {
                // 确保锁被释放
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }

    }

//    //创建一个阻塞队列
//    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
//    //处理阻塞队列中的订单信息
//    private class VoucherOrderHandler implements Runnable{
//
//        @Override
//        public void run() {
//            while (true) {
//                try {
//                    //1.获取队列中的订单信息
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    //2.创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {}
//            }
//        }
//
//        private void handleVoucherOrder(VoucherOrder voucherOrder) {
//            Long userId = voucherOrder.getUserId();
//            RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + userId);
//            boolean isLock = lock.tryLock();
//            if(!isLock){
//                log.error("不允许重复下单");
//                return;
//            }
//            try {
//                proxy.createVoucherOrder(voucherOrder);
//            }
//            finally {
//                lock.unlock();
//            }
//        }
//
//    }


    private IVoucherOrderService proxy;

    /**
     * 秒杀优惠券
     * @param voucherId 优惠券id
     * @return 订单id
     */
    public Result seckillVoucher(Long voucherId) {
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        //订单id
        long orderId = redisIdWorker.nextId("order");
        //使用lua脚本，将把订单放入stream的逻辑放到lua脚本中
        Long result = null;

        try {
            result = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(),
                    userId.toString(),
                    String.valueOf(orderId)
            );
        }  catch (Exception e) {
            log.error("lua脚本执行失败");
            throw new RuntimeException(e);
        }


        //判断结果是否为0

        //不为0，没有购买资格，返回错误信息
        if (result !=null && !result.equals(0L)) {
            int r = result.intValue();
            log.info(r == 1 ? "库存不足" : "不能重复下单");
            return Result.fail(r == 2 ? "不能重复下单" : "库存不足");
        }
        log.info("下单成功");
//
//        //0，有购买资格，把下单信息保存到阻塞队列中
//        VoucherOrder voucherOrder = new VoucherOrder();
//
//        voucherOrder.setId(orderId);
//        //用户id
//        voucherOrder.setUserId(userId);
//        //代金券id
//        voucherOrder.setVoucherId(voucherId);
//        //放入阻塞队列
//        orderTasks.add(voucherOrder);
        //获取代理对象，避免事务失效
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }



//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1. 查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//
//        //2.判断优惠券是否在售
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("优惠券尚未开始");
//        }
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("优惠券已过期");
//        }
//
//        //3.库存是否充足
//        Integer stock = voucher.getStock();
//        if (stock < 1) {
//            log.info("库存不足");
//            return Result.fail("库存不足");
//        }
//        Long userId = UserHolder.getUser().getId();
/// /        synchronized (userId.toString().intern()){
/// /            //使用Aopcontext获取代理对象，避免事务失效，这是由于springboot在开启事务时，
/// /            IVoucherOrderService proxy =(IVoucherOrderService) AopContext.currentProxy();
/// /            return proxy.createVoucherOrder(voucherId);
/// /        }
//        RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + userId);
//
//        boolean isLock = lock.tryLock();
//        if(!isLock){
//            //获取锁失败,返回错误信息,重试
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }
//        finally {
//            lock.unlock();
//        }
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long voucherId = voucherOrder.getVoucherId();

        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();

        // 2.创建订单（库存已经在Lua脚本中扣减）
        save(voucherOrder);
        log.info("订单创建成功，订单ID: {}", voucherOrder.getId());
    }

}

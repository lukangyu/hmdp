package com.hmdp.utils;

import cn.hutool.core.io.resource.ClassPathResource;
import cn.hutool.core.lang.UUID;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private StringRedisTemplate stringRedisTemplate;
    private String name;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation((Resource) new ClassPathResource("lua/unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程id
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁
        String key = KEY_PREFIX + name;
        Boolean flag = stringRedisTemplate.opsForValue().
                setIfAbsent(key, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag);
    }

    @Override
    public void unLock() {
        //调用lua脚本，可以满足原子性
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }

//为什么不用这个方法，因为会出现并发问题，因为在判断成功以后，出现阻塞，自动释放锁以后，其他线程拿到锁，会误删其他线程的锁
    //这种方法的问题在于它不是原子操作。它包含了三个独立的操作：
    //获取当前线程ID
    //从Redis中检索锁的值
    //比较它们并在匹配时删除锁
    //在步骤2和3之间存在一个竞态条件窗口：
    //线程A获取锁值并验证它与自己的ID匹配
    //线程A被挂起（由于GC、线程调度等原因）
    //锁自动过期（TTL到期）
    //线程B获取同一把锁
    //线程A恢复执行并删除锁，错误地删除了线程B的锁
    //这是一个典型的"检查时间到使用时间"(TOCTOU)竞态条件的例子。
//    @Override
//    public void unLock() {
//        //获取线程标识
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        //获取锁标识
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        if (threadId.equals(id)) {
//            //释放锁
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
//    }
}

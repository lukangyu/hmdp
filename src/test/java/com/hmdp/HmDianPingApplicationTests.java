package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private IUserService userService;

    /**
     * 1. 往 MySQL 数据库插入 1000 个假用户
     */
    @Test
    void generateUserData() {
        List<User> userList = new ArrayList<>();

        // 循环生成 1000 个用户
        for (int i = 1; i <= 1000; i++) {
            User user = new User();

            // 1. 设置手机号 (保证唯一，用 13800000 + i)
            user.setPhone("138" + String.format("%08d", i));

            // 2. 设置昵称 (user_xxxx)
            user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX +
                    cn.hutool.core.util.RandomUtil.randomString(6));

            // 3. 设置密码 (这里随便设一个，比如 123456 的 encoded 版本，或者如果不走登录逻辑直接设空也行)
            // 假设项目里没有复杂的加密校验，或者我们后续直接模拟登录跳过密码校验
            user.setPassword("123456");

            // 4. 设置创建和更新时间
            user.setCreateTime(LocalDateTime.now());
            user.setUpdateTime(LocalDateTime.now());

            userList.add(user);
        }

        // 5. 批量插入到数据库 (MyBatis Plus 提供的功能)
        // saveBatch 性能比在一个 for 循环里调用 save 要好得多
        userService.saveBatch(userList);

        System.out.println("成功插入 1000 条用户数据！");
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 批量生成用户登录Token，并保存到 tokens.txt 文件中
     */
    @Test
    void createToken() throws IOException {
        // 1. 从数据库查询所有用户 (假设你的 tb_user 表里已经导入了数据)
        // 如果数据量太大，可以用 .last("limit 1000") 来限制数量
        List<User> userList = userService.list();

        // 准备一个文件写入流，将 Token 写入到项目根目录下的 tokens.txt
        // 这样 JMeter 就可以直接读取这个文件了
        String filePath = "tokens.txt";
        BufferedWriter bw = new BufferedWriter(new FileWriter(filePath));

        for (User user : userList) {
            // 2. 将 User 对象转为 UserDTO (只保存关键信息，脱敏)
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

            // 3. 生成随机 Token (不需要带横线)
            String token = UUID.randomUUID().toString(true);

            // 4. 将 UserDTO 转为 Map，因为 StringRedisTemplate 的 Hash 结构要求 Key和Value 都是 String
            // 这里利用 Hutool 的 CopyOptions 自动把 Long 型的 id 转为 String
            Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                    CopyOptions.create()
                            .setIgnoreNullValue(true)
                            .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

            // 5. 保存到 Redis
            // Key 格式: login:token:xxxxxxxxxx
            String tokenKey = LOGIN_USER_KEY + token;

            // 5.1 存 Hash
            stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
            // 5.2 设有效期 (通常是30分钟，但测试可以设久一点)
            stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

            // 6. 将 Token 写入文件
            bw.write(token);
            bw.newLine(); // 换行
        }

        // 关流
        bw.close();
        System.out.println("数据预热完成！Token 已写入文件：" + filePath);
    }
}
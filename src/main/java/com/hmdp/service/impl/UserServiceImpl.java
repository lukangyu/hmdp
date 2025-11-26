package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;


import java.time.LocalDateTime;
import java.util.HashMap;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;//引入redis

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 发送验证码
        //校验
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        String code = RandomUtil.randomNumbers(6);

        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, RedisConstants.LOGIN_CODE_TTL, java.util.concurrent.TimeUnit.MINUTES);

        //TODO: 发送验证码
        log.debug("发送验证码成功，验证码：{}",  code);

        return Result.ok("发送验证码成功");

    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        //2.校验验证码,从redis中获取
        String code = loginForm.getCode();
        //Object cacheCode = session.getAttribute("code");//注意：这里为什么不使用string，因为session中存储的是object，且直接转化有可能code为null，所以这里使用object
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
//        if(cacheCode == null || !cacheCode.equals(code)){
//            return Result.fail("验证码错误");
//        }
        //3.查询用户
        User user = (User) query().eq("phone", phone).one();
        //如果不存在，创建新用户
        if(user == null){
            user = createUserWithPhone(phone);
        }
        //4.保存用户信息到redis中
        //随机生成token，作为用户的登录凭证
        String token = UUID.randomUUID().toString(true);
        //将user对象转化为hash储存
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY + token, BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().setFieldValueEditor((fieldName, fieldValue)  -> fieldValue.toString())));
        //设置token有效期,防止过多的用户登录，占据缓存空间
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + token, RedisConstants.LOGIN_USER_TTL, java.util.concurrent.TimeUnit.MINUTES);
        log.info("用户{}登录成功，生成token：{}", userDTO.getNickName(), token);
        //返回token，存储到HttpServletRequest中，退出登录时可以从request中获取，从redis中删除
        return Result.ok(token);
    }

    @Override
    public Result logout(HttpServletRequest request) {
        String name = UserHolder.getUser().getNickName();
        LocalDateTime now = LocalDateTime.now();
        String token = request.getHeader("authorization");
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.delete(tokenKey);
        log.info("{}用户{}退出登录", now, name);
        UserHolder.removeUser();
        return Result.ok("退出登录成功");
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);//保存用户,保存成功后，会返回一个id,赋给user,MybatisPlus会自动填充
        return user;
    }
}

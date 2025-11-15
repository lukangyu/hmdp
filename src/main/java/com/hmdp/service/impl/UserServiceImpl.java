package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

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

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 发送验证码
        //校验
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        String code = RandomUtil.randomNumbers(6);

        session.setAttribute("code",code);
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
        //2.校验验证码
        String code = loginForm.getCode();
        Object cacheCode = session.getAttribute("code");//注意：这里为什么不使用string，因为session中存储的是object，且直接转化有可能code为null，所以这里使用object
        if(cacheCode == null || !cacheCode.toString().equals(code)){
            return Result.fail("验证码错误");
        }
        //3.查询用户
        User user = (User) query().eq("phone", phone).one();
        //如果不存在，创建新用户
        if(user == null){
            user = createUserWithPhone(phone);
        }
        //4.保存用户信息到session中
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        return Result.ok();
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);//保存用户,保存成功后，会返回一个id,赋给user,MybatisPlus会自动填充
        return user;
    }
}

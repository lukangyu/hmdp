package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class LoginIntercepter implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1. 获取session
        Object user = request.getSession().getAttribute("user");
        if (user == null) {
            //2. 不存在，拦截， 返回401，未授权
            response.setStatus(401);
            return false;
        }
        //3. 存在，放行
        UserHolder.saveUser((UserDTO) user);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //移除用户
        UserHolder.removeUser();
    }
}

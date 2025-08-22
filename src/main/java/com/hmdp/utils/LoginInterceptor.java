package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
public class LoginInterceptor implements HandlerInterceptor {
    //controller之前
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1 获取session
        HttpSession session = request.getSession();

        //2 获取session中的用户
        Object user = session.getAttribute("user");

        //3 判断用户是否存在
        if(user == null){
            //4 不存在 拦截
            log.info("用户不存在");
            response.setStatus(401);
            return false;
        }

        //5 存在，保存用户到ThreadLocal
        log.info("用户存在");
        UserHolder.saveUser((UserDTO) user);

        //6 放行
        return true;
    }

    //用户登录完成之后进行销毁 避免内存泄露
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}

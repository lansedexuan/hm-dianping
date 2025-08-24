package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 添加拦截器
     * @param registry
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**")
                .order(0);//拦截所有请求 先执行
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(// 排除路径 以下路径放行
                        "/user/code",
                        "/user/login",
                        "blog/hot",
                        "shop/**",
                        "shop-type/**",
                        "upload/**",
                        "/voucher/**"
                )
                .order(1);//值越大 优先级越低
    }
}

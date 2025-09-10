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
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
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
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送验证码
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1 校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){// 正则判断
            //2 如果不符合 返回错误信息
            return Result.fail("手机号格式错误");
        }

        //3 如果符合 生成验证码
        String code = RandomUtil.randomNumbers(6);

        //4 保存验证码到session
        //session.setAttribute("code",code);

        //set key value ex 120
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //5 发送验证码(模拟)
        log.debug("发送短信验证码成功，验证码：{}",code);

        //返回
        return Result.ok();
    }

    /**
     * 登录功能
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1 校验手机号
        String phone=loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){// 正则判断
            //如果不符合 返回错误信息
            return Result.fail("手机号格式错误");
        }

        //2 从redis中获取验证码并校验
        String code=loginForm.getCode();//前端提交的code
        //Object cacheCode = session.getAttribute("code");//session保存的code
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);

        if(cacheCode == null || !cacheCode.equals(code)){//反向校验 避免if嵌套
            //3 不一致 报错
            return Result.fail("验证码错误");
        }

        //4 一致 根据手机号查询用户 select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();//mybatisplus: extends ServiceImpl实现单表增删改查
            //多个使用list

        //5 判断用户是否存在
        if(user == null){
            //6 不存在 创建新用户并保存到数据库
            user = createUserWithPhone(phone);
        }

        //7 保存用户信息到redis
        //7.1 随机生成token
        String token = UUID.randomUUID().toString(true);//true 格式为去掉-
        //7.2 将user对象转为hash结构
        UserDTO userDTO =BeanUtil.copyProperties(user, UserDTO.class);
/*      Map<String, Object> userMap = new HashMap<>();
        userMap.put("id", userDTO.getId() != null ? Long.toString(userDTO.getId()) : null);
        userMap.put("nickName", userDTO.getNickName());
        userMap.put("icon", userDTO.getIcon());*/
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));//beanToMap: userDTO转为map
        //7.3 存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);//1个key 多个value
        log.info("用户登录成功，已存入redis: {}", user.getId());
        //7.4 设置token有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL, TimeUnit.MINUTES);

        //session.setAttribute("user",user);
        //UserDTO userDTO = new UserDTO();
        //session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        //log.info("用户登录成功，已存入session: {}", user.getId());

        //8 返回token
        return Result.ok(token);//session不需要返回用户凭证
    }

    @Override
    public Result sign() {
        //1 获取当前登录用户
        Long userId = UserHolder.getUser().getId();

        //2 获取日期
        LocalDateTime now = LocalDateTime.now();

        //3 拼接Key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;

        //4 获取今天是本月第几天
        int dayOfMonth = now.getDayOfMonth();

        //5 写入redis SETBIT key offset 1
        //offset: 0-30 dayOfMonth表示1-31
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);

        return Result.ok();
    }

    @Override
    public Result signCount() {
        //1 获取当前登录用户
        Long userId = UserHolder.getUser().getId();

        //2 获取日期
        LocalDateTime now = LocalDateTime.now();

        //3 拼接Key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;

        //4 获取今天是本月第几天
        int dayOfMonth = now.getDayOfMonth();

        //5 获取本月截止今天为止的所有的签到记录，返回的是一个十进制数字
        List<Long> result = stringRedisTemplate.opsForValue().bitField(//BITFIELD sign:5:2025 GET u14 0
                key,
                BitFieldSubCommands.create().
                        get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)//GET u14 0
        );
        if (result == null || result.isEmpty()) {
            //没有签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if(num == null || num == 0L){
            return Result.ok(0);
        }

        //6 循环遍历
        int count = 0;
        while(true){
            //7 让这个数字与1做位运算，得到第几天是1
            if((num & 1) == 0){
                //8 如果是0，说明未签到，结束
                break;
            }else{
                //9 如果是1，说明已签到，计数器+1
                count++;
            }
            //10 把数字右移一位 摒弃最后一个bit位，继续下一个bit位
            //num = num >> 1; //>> —— 算术右移 保留符号位 如果是正数，高位补 0; 如果是负数，高位补 1。
            num>>>=1;         //>>> —— 逻辑右移 符号位丢失 高位一律补 0
        }

        return Result.ok(count);
    }

    /**
     * 创建新用户
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone) {
        //1 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX +RandomUtil.randomString(10));
        //2 保存用户
        save(user);
        return user;
    }
}

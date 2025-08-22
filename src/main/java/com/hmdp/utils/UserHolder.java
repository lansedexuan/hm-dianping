package com.hmdp.utils;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;

public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    //保存用户
    public static void saveUser(UserDTO userId){
        tl.set(userId);
    }

    //获取用户
    public static UserDTO getUser(){
        return tl.get();
    }

    //删除用户
    public static void removeUser(){
        tl.remove();
    }
}

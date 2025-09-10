package com.hmdp.dto;

import lombok.Data;

import java.util.List;

@Data//Data注解：创建一个类，这个类有getter和setter方法，构造方法，toString方法，hashCode方法，equals方法
public class ScrollResult {
    private List<?> list;
    private Long minTime;
    private Integer offset;
}

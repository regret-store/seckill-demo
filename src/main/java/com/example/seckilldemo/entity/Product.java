package com.example.seckilldemo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("product") // 明确指定表名，好习惯
public class Product {

    @TableId(type = IdType.AUTO) // 指定主键自增
    private Long id;

    private String name;

    private Integer stock;

    private BigDecimal price;

    private LocalDateTime createTime;
}
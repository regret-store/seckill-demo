package com.example.seckilldemo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("seckill_order")
public class SeckillOrder {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long activityId; // 简化起见，这里暂时传个固定值如1
    private Long productId;
    private Integer status;
    private LocalDateTime createTime;

    @com.baomidou.mybatisplus.annotation.Version
    private Integer version;
}
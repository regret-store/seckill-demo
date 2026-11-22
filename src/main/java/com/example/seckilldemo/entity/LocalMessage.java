package com.example.seckilldemo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("local_message")
public class LocalMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long productId;
    private Integer status; //-- 0:待发送 1:已发送成功 2:发送失败(可重试) 3:彻底失败(需人工)
    private Integer retryCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
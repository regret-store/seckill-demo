package com.example.seckilldemo.event;

import lombok.Data;

@Data
public class SeckillMessageEvent {
    private Long userId;
    private Long productId;

    public SeckillMessageEvent(Long userId,Long productId)
    {
        this.userId = userId;
        this.productId = productId;
    }
}

package com.example.seckilldemo.event;

import lombok.Data;

@Data
public class SeckillDelayEvent {
    private Long userId;
    private Long productId;
    public SeckillDelayEvent(Long userId,Long productId)
    {
        this.userId = userId;
        this.productId = productId;
    }
}

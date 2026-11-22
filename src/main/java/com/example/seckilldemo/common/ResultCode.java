package com.example.seckilldemo.common;

import lombok.Getter;

@Getter
public enum ResultCode {
    SUCCESS(200, "操作成功"),
    LIMIT_ERROR(429, "请求过于频繁，请稍后再试"),
    SECKILL_FAIL(5001, "秒杀失败"),
    STOCK_EMPTY(5002, "库存不足"),
    REPEAT_BUY(5003, "请勿重复下单"),
    SYSTEM_ERROR(500, "系统内部异常"),
    PRODUCT_NOT_FOUND(5004, "商品不存在");

    private final int code;
    private final String msg;

    ResultCode(int code,String msg)
    {
        this.code = code;
        this.msg = msg;
    }
}

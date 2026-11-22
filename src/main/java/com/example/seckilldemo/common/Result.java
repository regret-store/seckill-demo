package com.example.seckilldemo.common;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor        // ← 加这个
public class Result<T> {
    private int code;
    private String msg;
    private T data;

    // 构造方法私有化，强制使用静态方法创建
    private Result(int code,String msg,T data)
    {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    // 成功，无数据返回
    public static <T> Result<T> success()
    {
        return new Result<>(ResultCode.SUCCESS.getCode(),ResultCode.SUCCESS.getMsg(),null);
    }

    // 成功，带数据返回
    public static <T> Result<T> success(T data)
    {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMsg(), data);
    }

    // 失败，使用预设枚举
    public static <T> Result<T> error(ResultCode resultCode)
    {
        return new Result<>(resultCode.getCode(), resultCode.getMsg(), null);
    }

    // 失败，自定义错误信息
    public static <T> Result<T> error(ResultCode resultCode,String msg)
    {
        return new Result<>(resultCode.getCode(),msg,null);
    }
}

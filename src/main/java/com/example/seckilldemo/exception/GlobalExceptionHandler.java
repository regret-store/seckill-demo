package com.example.seckilldemo.exception;

import com.example.seckilldemo.common.Result;
import com.example.seckilldemo.common.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice // 拦截所有 Controller 抛出的异常
public class GlobalExceptionHandler {

    // 捕获你专门抛出的限流异常
    @ExceptionHandler(LimitException.class)
    public Result<Void> handleLimitException(LimitException e)
    {
        return Result.error(ResultCode.LIMIT_ERROR,e.getMessage());
    }

    // 捕获业务异常（比如你抛出的库存不足）
    @ExceptionHandler(RuntimeException.class)
    public Result<Void> handleRuntimeException(RuntimeException e) {
        log.error("业务异常：",e);
        return Result.error(ResultCode.SYSTEM_ERROR,e.getMessage());
    }

    // 捕获所有未知异常（兜底）
    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e)
    {
        log.error("系统级异常: ", e);
        return Result.error(ResultCode.SYSTEM_ERROR);
    }
}
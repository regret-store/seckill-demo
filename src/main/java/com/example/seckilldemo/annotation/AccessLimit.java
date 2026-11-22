package com.example.seckilldemo.annotation;

import java.lang.annotation.*;

/**
 * 接口限流防刷注解
 */
@Target(ElementType.METHOD) // 这个注解只能加在方法上
@Retention(RetentionPolicy.RUNTIME) // 注解信息保留到运行时，这样程序可以通过反射读到它（限流逻辑必须靠反射读配置，所以必须 RUNTIME）
@Documented //生成 Javadoc 时把这个注解也显示出来，纯文档作用
public @interface AccessLimit {
    /**
     * 限制的时间窗口（秒），默认 1 秒
     */
    int seconds() default 1;

    /**
     * 时间窗口内允许的最大访问次数，默认 5 次
     */
    int maxCount() default 5;
}

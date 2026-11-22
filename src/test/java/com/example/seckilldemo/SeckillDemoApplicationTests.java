package com.example.seckilldemo;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@Disabled("需要 MySQL + Redis + MQ 环境，本地手工压测用")
@SpringBootTest
class SeckillDemoApplicationTests {

    @Test
    void contextLoads() {
    }

}

package com.example.seckilldemo.controller;

import com.example.seckilldemo.mapper.ProductMapper;
import com.example.seckilldemo.service.LocalMessageService;
import com.example.seckilldemo.service.SeckillService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SeckillController.class)
class SeckillControllerTest {

    @Autowired MockMvc mvc;
    @MockBean StringRedisTemplate redisTemplate;
    @MockBean LocalMessageService localMessageService;
    @MockBean ProductMapper productMapper;
    @MockBean SeckillService seckillService;

    private void stubLuaReturn(Long code) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(code);
    }

    @Test
    @DisplayName("Lua 返回码 → 业务码映射，且 HTTP 一律 200（429 是业务码不是状态码）")
    void mapsLuaCodesToBusinessCodes() throws Exception {
        record Case(long luaCode, int expectBizCode) {}
        for (Case c : List.of(new Case(0L, 200), new Case(1L, 5002),
                new Case(2L, 5003), new Case(3L, 5001), new Case(4L, 5001))) {
            stubLuaReturn(c.luaCode());
            mvc.perform(get("/seckill/test").param("productId", "1").param("userId", "1001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(c.expectBizCode()));
        }
        // 只有 Lua 放行（返回 0）时才允许写本地消息表
        verify(localMessageService, times(1)).saveLocalMessage(1001L, 1L);
    }

    @Test
    @DisplayName("分桶索引由 userId % 10 决定，作为 ARGV 传给脚本")
    void passesBucketIndexDerivedFromUserId() throws Exception {
        stubLuaReturn(1L);
        mvc.perform(get("/seckill/test").param("productId", "1").param("userId", "1007"))
                .andExpect(status().isOk());
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("1")), eq("1007"), eq("7"));
    }

    @Test
    @DisplayName("脚本返回 null（Redis 异常）→ SYSTEM_ERROR，且不落本地消息表")
    void nullFromRedisIsSystemError() throws Exception {
        stubLuaReturn(null);
        mvc.perform(get("/seckill/test").param("productId", "1").param("userId", "1001"))
                .andExpect(jsonPath("$.code").value(500));
        verify(localMessageService, never()).saveLocalMessage(anyLong(), anyLong());
    }
}
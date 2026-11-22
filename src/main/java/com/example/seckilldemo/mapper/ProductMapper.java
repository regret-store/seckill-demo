package com.example.seckilldemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.seckilldemo.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    // 自定义扣减库存 SQL，直接在数据库层面保证原子性和安全性
    @Update("UPDATE product SET stock = stock -1 WHERE id = #{id} AND stock > 0")
    int deductStock(@Param("id")Long id);

    // 恢复库存
    @Update("UPDATE product SET stock = stock + 1 WHERE id = #{id}")
    int recoverStock(@Param("id") Long id);
}
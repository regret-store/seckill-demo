package com.example.seckilldemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.seckilldemo.entity.LocalMessage;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LocalMessageMapper extends BaseMapper<LocalMessage> {
}
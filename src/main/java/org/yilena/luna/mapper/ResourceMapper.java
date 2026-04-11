package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.yilena.luna.entity.Resource;

@Mapper
/**
 * 统一资源 Mapper，负责对统一资源实体执行基础持久化操作，
 * 为能力检索、搜索和资源详情展示提供底层支持。
 */
public interface ResourceMapper extends BaseMapper<Resource> {
}

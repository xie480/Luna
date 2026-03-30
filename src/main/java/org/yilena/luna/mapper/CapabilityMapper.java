package org.yilena.luna.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface CapabilityMapper {

    @Select("""
            select capability_id, capability_type, capability_name, title, description, requires_approval, sensitivity
            from capability_registry
            where enabled = true
            order by capability_type asc, capability_name asc
            limit 24
            """)
    List<Map<String, Object>> selectTopCapabilities();
}

package org.yilena.luna.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LegacyMcpReadMetricMapper {

    @Insert("""
            insert into legacy_mcp_read_metric(app_version, legacy_table, read_count, first_seen_at, last_seen_at)
            values (#{appVersion}, #{legacyTable}, 1, current_timestamp, current_timestamp)
            on conflict (app_version, legacy_table)
            do update set
              read_count = legacy_mcp_read_metric.read_count + 1,
              last_seen_at = current_timestamp
            """)
    int bumpRead(@Param("appVersion") String appVersion, @Param("legacyTable") String legacyTable);
}


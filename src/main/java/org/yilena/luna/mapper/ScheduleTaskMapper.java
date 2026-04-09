package org.yilena.luna.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.yilena.luna.entity.ScheduleTask;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface ScheduleTaskMapper extends BaseMapper<ScheduleTask> {

    @Select("""
            select id, content, trigger_time, status, task_type, created_at, updated_at
            from schedule_task
            where coalesce(deleted, 0) = 0
              and trigger_time >= #{start}
              and trigger_time < #{end}
            order by trigger_time asc
            limit #{limit}
            """)
    List<Map<String, Object>> selectResourceScheduleByTriggerBetween(@Param("start") LocalDateTime start,
                                                                     @Param("end") LocalDateTime end,
                                                                     @Param("limit") int limit);

    @Select("""
            select id, content, trigger_time, status, task_type, created_at, updated_at
            from schedule_task
            where coalesce(deleted, 0) = 0
              and id = #{taskId}
            limit 1
            """)
    List<Map<String, Object>> selectResourceScheduleById(@Param("taskId") Long taskId);

    @Delete("""
            delete from schedule_task
            where id = #{taskId}
            """)
    int hardDeleteById(@Param("taskId") Long taskId);
}

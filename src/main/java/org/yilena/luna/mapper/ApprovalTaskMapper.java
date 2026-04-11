package org.yilena.luna.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Map;

@Mapper
/**
 * 审批任务 Mapper，负责读写需要人工审批的任务记录及其执行状态。
 */
public interface ApprovalTaskMapper {

    @Insert("""
            insert into tasks (task_id, resource_id, status, server_code, tool_name, approval_id, session_id, input_args, approval_payload)
            values (#{taskId}, #{resourceId}, #{status}, #{serverCode}, #{toolName}, #{approvalId}, #{sessionId}, #{inputArgs}, #{approvalPayload})
            on conflict (task_id) do update set
                resource_id = excluded.resource_id,
                status = excluded.status,
                server_code = excluded.server_code,
                tool_name = excluded.tool_name,
                approval_id = excluded.approval_id,
                session_id = excluded.session_id,
                input_args = excluded.input_args,
                approval_payload = excluded.approval_payload,
                updated_at = current_timestamp
            """)
    /**
     * 新增或更新审批任务，确保同一任务标识在表中只保留一条最新记录。
     */
    int upsertTask(@Param("taskId") Long taskId,
                   @Param("resourceId") Long resourceId,
                   @Param("status") String status,
                   @Param("serverCode") String serverCode,
                   @Param("toolName") String toolName,
                   @Param("approvalId") String approvalId,
                   @Param("sessionId") String sessionId,
                   @Param("inputArgs") String inputArgs,
                   @Param("approvalPayload") String approvalPayload);

    @Select("""
            select task_id, resource_id, status, server_code, tool_name, session_id, input_args, result, error_code, approval_payload
            from tasks
            where task_id = #{taskId}
            limit 1
            """)
    /**
     * 按任务标识查询审批任务详情。
     */
    Map<String, Object> selectTaskById(@Param("taskId") Long taskId);

    @Update("""
            update tasks
            set status = #{status},
                result = coalesce(#{result}, result),
                error_code = #{errorCode},
                updated_at = current_timestamp
            where task_id = #{taskId}
            """)
    /**
     * 更新审批任务执行状态，并按需补充执行结果和错误码。
     */
    int updateTaskStatus(@Param("taskId") Long taskId,
                         @Param("status") String status,
                         @Param("result") String result,
                         @Param("errorCode") String errorCode);
}

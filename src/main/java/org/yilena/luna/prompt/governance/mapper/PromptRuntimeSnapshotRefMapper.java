package org.yilena.luna.prompt.governance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.yilena.luna.prompt.governance.entity.PromptRuntimeSnapshotRefEntity;

@Mapper
/**
 * 运行时提示快照引用 Mapper，负责对运行时提示快照引用实体执行基础持久化操作，
 * 用于追踪某次运行快照命中的提示词版本关系。
 */
public interface PromptRuntimeSnapshotRefMapper extends BaseMapper<PromptRuntimeSnapshotRefEntity> {
}

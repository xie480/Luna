package org.yilena.luna.prompt.governance;

import org.yilena.luna.prompt.governance.dto.PromptSearchRequest;
import org.yilena.luna.prompt.governance.model.PromptItemRecord;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 提示词查询服务接口，负责分类列表、键值视图、检索和详情查询能力，
 * 为治理后台和运行时查询提供统一读取入口。
 */
public interface PromptQueryService {
    List<String> listCategories();

    Map<String, String> listKeyValueByCategory(String category, String subCategory);

    List<PromptItemRecord> search(PromptSearchRequest request);

    Optional<PromptItemRecord> detailByKey(String key);
}

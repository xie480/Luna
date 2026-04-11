package org.yilena.luna.prompt.governance;

import org.yilena.luna.prompt.governance.model.PromptItemRecord;

import java.util.List;
import java.util.Map;

/**
 * 提示词前端适配接口，负责把提示词记录转换为前端更易消费的分类键值视图，
 * 统一后台记录到展示模型之间的数据结构。
 */
public interface PromptFrontendAdapter {
    Map<String, Object> toCategoryKeyValueView(String category, String subCategory, List<PromptItemRecord> rows);
}

package org.yilena.luna.context;

/**
 * 该常量类用于维护上下文重构与裁剪阶段使用的关键词词典，为时间范围、约束识别和异常恢复提供基础语义信号。
 */
public final class Lexicon {

    private Lexicon() {
    }

    /**
     * 用于识别“今天”时间范围的关键词集合。
     */
    public static final String[] TIME_SCOPE_TODAY_KEYWORDS = {"today", "今天", "今晚"};
    /**
     * 用于识别“明天”时间范围的关键词集合。
     */
    public static final String[] TIME_SCOPE_TOMORROW_KEYWORDS = {"tomorrow", "明天"};
    /**
     * 用于识别“本周”时间范围的关键词集合。
     */
    public static final String[] TIME_SCOPE_THIS_WEEK_KEYWORDS = {"this week", "本周", "这周"};
    /**
     * 用于识别“本月”时间范围的关键词集合。
     */
    public static final String[] TIME_SCOPE_THIS_MONTH_KEYWORDS = {"this month", "本月", "这个月"};

    /**
     * 用于识别对当前对象、上一轮结果或同一目标的指代关键词集合。
     */
    public static final String[] TARGET_REFERENCE_KEYWORDS = {"这个", "那个", "it", "this", "that", "再来一次", "继续", "same as before"};
    /**
     * 用于识别用户动作诉求的关键词集合。
     */
    public static final String[] TASK_ACTION_KEYWORDS = {"帮我", "please", "分析", "处理", "solve", "optimize"};
    /**
     * 用于识别目标询问和标准询问的关键词集合。
     */
    public static final String[] GOAL_QUERY_KEYWORDS = {"怎么", "what", "如何", "目标", "标准", "criteria"};
    /**
     * 用于触发业务约束识别的关键词集合。
     */
    public static final String[] CONSTRAINT_TRIGGER_KEYWORDS = {"必须", "must", "不要", "预算", "deadline", "截止"};
    /**
     * 用于识别强约束或禁止性条件的关键词集合。
     */
    public static final String[] HARD_CONSTRAINT_KEYWORDS = {"不要", "别", "must", "必须", "only", "仅", "deadline", "截止", "预算", "budget"};

    /**
     * 用于识别超时类恢复场景的关键词集合。
     */
    public static final String[] RECOVERY_TIMEOUT_KEYWORDS = {"timeout", "expired", "过期", "超时"};
    /**
     * 用于识别数据结构变化或校验冲突场景的关键词集合。
     */
    public static final String[] RECOVERY_DATA_MUTATION_KEYWORDS = {"schema", "validation", "变更", "冲突"};
    /**
     * 用于识别失败和异常恢复场景的关键词集合。
     */
    public static final String[] RECOVERY_FAILURE_KEYWORDS = {"failed", "error", "失败", "异常"};

    /**
     * 中文关键事实识别正则片段，用于约束、风险和待办状态等信息抽取。
     */
    public static final String KEY_FACT_CHINESE_PATTERN = "必须|不要|截止|预算|风险|状态|待处理";
}

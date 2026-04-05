package org.yilena.luna.context;

public final class Lexicon {

    private Lexicon() {
    }

    public static final String[] TIME_SCOPE_TODAY_KEYWORDS = {"today", "今天", "今晚"};
    public static final String[] TIME_SCOPE_TOMORROW_KEYWORDS = {"tomorrow", "明天"};
    public static final String[] TIME_SCOPE_THIS_WEEK_KEYWORDS = {"this week", "本周", "这周"};
    public static final String[] TIME_SCOPE_THIS_MONTH_KEYWORDS = {"this month", "本月", "这个月"};

    public static final String[] TARGET_REFERENCE_KEYWORDS = {"这个", "那个", "it", "this", "that", "再来一次", "继续", "same as before"};
    public static final String[] TASK_ACTION_KEYWORDS = {"帮我", "please", "分析", "处理", "solve", "optimize"};
    public static final String[] GOAL_QUERY_KEYWORDS = {"怎么", "what", "如何", "目标", "标准", "criteria"};
    public static final String[] CONSTRAINT_TRIGGER_KEYWORDS = {"必须", "must", "不要", "预算", "deadline", "截止"};
    public static final String[] HARD_CONSTRAINT_KEYWORDS = {"不要", "别", "must", "必须", "only", "仅", "deadline", "截止", "预算", "budget"};

    public static final String[] RECOVERY_TIMEOUT_KEYWORDS = {"timeout", "expired", "过期", "超时"};
    public static final String[] RECOVERY_DATA_MUTATION_KEYWORDS = {"schema", "validation", "变更", "冲突"};
    public static final String[] RECOVERY_FAILURE_KEYWORDS = {"failed", "error", "失败", "异常"};

    public static final String KEY_FACT_CHINESE_PATTERN = "必须|不要|截止|预算|风险|状态|待处理";
}


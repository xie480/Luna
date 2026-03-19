# MCP 工具注册清单 (JSON 格式)

以下是所有工具的 JSON 定义。你可以直接复制每个代码块中的 JSON 对象进行注册。

> **提示**：`inputSchema` 和 `outputSchema` 在此处展示为嵌套对象 (Object) 以便于阅读和编辑。如果你的后端接口要求这两个字段必须是 String 类型，请在前端发送请求前将其转换为字符串 (JSON.stringify)。

---

## 1. 用户偏好设置 (PreferenceTools)

```json
{
  "name": "manage_user_preference",
  "description": "用于管理和持久化用户的个性化偏好设置。当用户明确要求记住某些习惯（如语言、主题、特定格式要求）或你需要查询用户之前的设置时调用。支持 INSERT(新增偏好), QUERY(按键名查询), UPDATE(修改现有偏好), DELETE(删除偏好)。",
  "version": "1.0.0",
  "owner": "System",
  "beanName": "preferenceTools",
  "methodName": "manageUserPreference",
  "inputSchema": {
    "type": "object",
    "properties": {
      "action": {
        "type": "string",
        "enum": ["INSERT", "QUERY", "UPDATE", "DELETE"],
        "description": "操作类型"
      },
      "id": {
        "type": "integer",
        "description": "记录ID (UPDATE/DELETE 操作必须)"
      },
      "mode": {
        "type": "string",
        "enum": ["PUT", "PATCH"],
        "description": "更新模式 (PUT:全量替换, PATCH:部分更新)"
      },
      "prefKey": {
        "type": "string",
        "description": "偏好设置的键名 (Key)"
      },
      "prefValue": {
        "type": "string",
        "description": "偏好设置的值 (Value)"
      },
      "description": {
        "type": "string",
        "description": "备注描述"
      },
      "hardDelete": {
        "type": "boolean",
        "description": "是否物理删除 (仅 DELETE 操作有效)"
      }
    },
    "required": ["action"]
  },
  "outputSchema": {
    "type": "object",
    "properties": {
      "status": { "type": "string", "description": "success 或 error" },
      "data": { "type": "object", "description": "UserPreference 对象或列表" }
    }
  }
}
```

---

## 2. 网页搜索 (SearchTools - web_search)

```json
{
  "name": "web_search",
  "description": "执行通用互联网网页搜索。当你需要获取最新资讯、实时数据、或者你自身知识库中缺乏的外部事实时调用此工具。输入精准的搜索关键词，返回相关的网页链接和摘要片段。",
  "version": "1.0.0",
  "owner": "System",
  "beanName": "searchTools",
  "methodName": "web_search",
  "inputSchema": {
    "type": "object",
    "properties": {
      "query": {
        "type": "string",
        "description": "搜索关键词"
      }
    },
    "required": ["query"]
  },
  "outputSchema": {
    "type": "object",
    "properties": {
      "organic": { "type": "array", "description": "搜索结果列表" },
      "peopleAlsoAsk": { "type": "array", "description": "相关问题" }
    }
  }
}
```

---

## 3. 图片搜索 (SearchTools - image_search)

```json
{
  "name": "image_search",
  "description": "执行互联网图片搜索。当用户明确要求寻找某种图片、照片、插图，或者需要视觉参考资料时调用。输入描述性的关键词，返回相关图片的 URL 和标题。",
  "version": "1.0.0",
  "owner": "System",
  "beanName": "searchTools",
  "methodName": "image_search",
  "inputSchema": {
    "type": "object",
    "properties": {
      "query": {
        "type": "string",
        "description": "图片搜索关键词"
      }
    },
    "required": ["query"]
  },
  "outputSchema": {
    "type": "object",
    "properties": {
      "images": { "type": "array", "description": "图片结果列表，包含 URL 和标题" }
    }
  }
}
```

---

## 4. 新闻搜索 (SearchTools - news_search)

```json
{
  "name": "news_search",
  "description": "执行互联网新闻搜索。当用户询问最近发生的事件、时事热点、行业动态或特定主题的最新报道时调用。返回包含标题、来源和发布时间的新闻列表。",
  "version": "1.0.0",
  "owner": "System",
  "beanName": "searchTools",
  "methodName": "news_search",
  "inputSchema": {
    "type": "object",
    "properties": {
      "query": {
        "type": "string",
        "description": "新闻搜索关键词"
      }
    },
    "required": ["query"]
  },
  "outputSchema": {
    "type": "object",
    "properties": {
      "news": { "type": "array", "description": "新闻结果列表，包含标题、来源、时间" }
    }
  }
}
```

---

## 5. 以图搜图 (SearchTools - lens_search)

```json
{
  "name": "lens_search",
  "description": "执行以图搜图 (Google Lens) 功能。当用户提供了一个图片 URL 并要求识别图片内容、寻找相似图片、或者获取图片中物品的相关信息时调用。必须提供有效的图片 URL。",
  "version": "1.0.0",
  "owner": "System",
  "beanName": "searchTools",
  "methodName": "lens_search",
  "inputSchema": {
    "type": "object",
    "properties": {
      "url": {
        "type": "string",
        "description": "图片的 URL 地址"
      }
    },
    "required": ["url"]
  },
  "outputSchema": {
    "type": "object",
    "description": "Google Lens 识别结果"
  }
}
```

---

## 6. 网页抓取 (SearchTools - web_scrape)

```json
{
  "name": "web_scrape",
  "description": "抓取并提取指定网页的纯文本内容。当你通过搜索工具获得了一个 URL，且需要深入阅读该网页的详细内容以回答用户问题时，或者用户直接提供 URL 要求总结/提取信息时调用。",
  "version": "1.0.0",
  "owner": "System",
  "beanName": "searchTools",
  "methodName": "web_scrape",
  "inputSchema": {
    "type": "object",
    "properties": {
      "url": {
        "type": "string",
        "description": "目标网页 URL"
      }
    },
    "required": ["url"]
  },
  "outputSchema": {
    "type": "object",
    "properties": {
      "text": { "type": "string", "description": "网页纯文本内容" },
      "metadata": { "type": "object", "description": "网页元数据" }
    }
  }
}
```

---

## 7. 日程任务管理 (ScheduleTools)

```json
{
  "name": "manage_schedule_task",
  "description": "用于管理用户的日程安排和定时任务。当用户要求设置提醒、安排会议、创建定时执行的任务，或者查询/修改现有日程时调用。支持 INSERT(创建任务), QUERY(查询任务), UPDATE(修改状态或时间), DELETE(取消任务)。需严格遵守 yyyy-MM-dd HH:mm:ss 的时间格式。",
  "version": "1.0.0",
  "owner": "System",
  "beanName": "scheduleTools",
  "methodName": "manageScheduleTask",
  "inputSchema": {
    "type": "object",
    "properties": {
      "action": {
        "type": "string",
        "enum": ["INSERT", "QUERY", "UPDATE", "DELETE"],
        "description": "操作类型"
      },
      "id": {
        "type": "integer",
        "description": "任务ID (UPDATE/DELETE 操作必须)"
      },
      "mode": {
        "type": "string",
        "enum": ["PUT", "PATCH"],
        "description": "更新模式"
      },
      "content": {
        "type": "string",
        "description": "任务内容"
      },
      "triggerTime": {
        "type": "string",
        "description": "触发时间，格式: yyyy-MM-dd HH:mm:ss"
      },
      "status": {
        "type": "string",
        "enum": ["PENDING", "RUNNING", "COMPLETED", "FAILED", "CANCELLED"],
        "description": "任务状态"
      },
      "taskType": {
        "type": "string",
        "enum": ["ONCE", "CRON", "INTERVAL"],
        "description": "任务类型"
      },
      "hardDelete": {
        "type": "boolean",
        "description": "是否物理删除"
      }
    },
    "required": ["action"]
  },
  "outputSchema": {
    "type": "object",
    "properties": {
      "status": { "type": "string" },
      "data": { "type": "object", "description": "ScheduleTask 对象或列表" }
    }
  }
}
```

---

## 8. 知识库管理 (KnowledgeBaseTools)

```json
{
  "name": "manage_knowledge_base",
  "description": "用于管理系统的 RAG (检索增强生成) 知识库。当用户提供长篇文档、重要资料需要长期存档时调用 INSERT 写入；当你需要回答特定领域问题、回忆之前存储的专属知识时调用 QUERY 进行语义向量检索。",
  "version": "1.0.0",
  "owner": "System",
  "beanName": "knowledgeBaseTools",
  "methodName": "manageKnowledgeBase",
  "inputSchema": {
    "type": "object",
    "properties": {
      "action": {
        "type": "string",
        "enum": ["INSERT", "QUERY"],
        "description": "操作类型"
      },
      "title": {
        "type": "string",
        "description": "知识标题 (INSERT 必须)"
      },
      "content": {
        "type": "string",
        "description": "知识内容文本 (INSERT 必须)"
      },
      "sourceType": {
        "type": "string",
        "enum": ["TEXT", "FILE", "URL"],
        "description": "来源类型"
      },
      "sourcePath": {
        "type": "string",
        "description": "来源路径或链接"
      },
      "query": {
        "type": "string",
        "description": "检索关键词 (QUERY 必须)"
      }
    },
    "required": ["action"]
  },
  "outputSchema": {
    "type": "object",
    "properties": {
      "status": { "type": "string" },
      "data": { "type": "array", "description": "检索到的知识片段列表或操作结果消息" }
    }
  }
}
```

---

## 9. 长期记忆管理 (MemoryTools)

```json
{
  "name": "manage_memory",
  "description": "用于管理 AI 与用户对话的长期记忆。当需要记住用户的个人画像(USER_PROFILE)、对话总结(CONVERSATION_SUMMARY)或关键事实(FACT)以便未来对话使用时调用。这有助于保持跨会话的上下文连贯性。支持增删改查操作。",
  "version": "1.0.0",
  "owner": "System",
  "beanName": "memoryTools",
  "methodName": "manageMemory",
  "inputSchema": {
    "type": "object",
    "properties": {
      "action": {
        "type": "string",
        "enum": ["INSERT", "QUERY", "UPDATE", "DELETE"],
        "description": "操作类型"
      },
      "id": {
        "type": "integer",
        "description": "记忆ID (UPDATE/DELETE 必须)"
      },
      "mode": {
        "type": "string",
        "enum": ["PUT", "PATCH"],
        "description": "更新模式"
      },
      "sessionId": {
        "type": "string",
        "description": "会话标识 ID"
      },
      "memoryType": {
        "type": "string",
        "enum": ["USER_PROFILE", "CONVERSATION_SUMMARY", "FACT", "PREFERENCE"],
        "description": "记忆类型"
      },
      "content": {
        "type": "string",
        "description": "记忆内容"
      },
      "weight": {
        "type": "integer",
        "description": "记忆权重 (默认1)"
      },
      "hardDelete": {
        "type": "boolean",
        "description": "是否物理删除"
      }
    },
    "required": ["action"]
  },
  "outputSchema": {
    "type": "object",
    "properties": {
      "status": { "type": "string" },
      "data": { "type": "object", "description": "Memory 对象或列表" }
    }
  }
}
```

---

## 10. 系统日志管理 (LogTools)

```json
{
  "name": "manage_log",
  "description": "用于查询和管理系统的运行日志。当需要排查错误、审计系统行为、查看工具调用历史或用户操作记录时调用。支持按日志类型、模块、时间段进行 QUERY 查询，也支持 INSERT 写入自定义日志或 DELETE 清理过期日志。",
  "version": "1.0.0",
  "owner": "System",
  "beanName": "logTools",
  "methodName": "manageLog",
  "inputSchema": {
    "type": "object",
    "properties": {
      "action": {
        "type": "string",
        "enum": ["INSERT", "QUERY", "DELETE"],
        "description": "操作类型"
      },
      "logType": {
        "type": "string",
        "enum": ["SYSTEM_EVENT", "USER_ACTION", "ERROR", "TOOL_CALL"],
        "description": "日志类型"
      },
      "module": {
        "type": "string",
        "description": "所属模块"
      },
      "content": {
        "type": "string",
        "description": "日志内容 (INSERT 用)"
      },
      "startTime": {
        "type": "string",
        "description": "查询开始时间 (yyyy-MM-dd HH:mm:ss)"
      },
      "endTime": {
        "type": "string",
        "description": "查询结束时间 (yyyy-MM-dd HH:mm:ss)"
      },
      "limit": {
        "type": "integer",
        "description": "返回条数限制 (默认10)"
      },
      "id": {
        "type": "integer",
        "description": "日志ID (DELETE 用)"
      },
      "beforeTime": {
        "type": "string",
        "description": "删除此时间之前的日志 (DELETE 用)"
      }
    },
    "required": ["action"]
  },
  "outputSchema": {
    "type": "object",
    "properties": {
      "status": { "type": "string" },
      "data": { "type": "object", "description": "LunaLog 对象列表或操作消息" }
    }
  }
}

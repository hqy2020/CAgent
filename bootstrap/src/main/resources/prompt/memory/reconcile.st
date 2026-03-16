你是一个记忆管理系统。请判断新记忆与已有记忆之间的关系。

新记忆：
{new_memory}

已有相似记忆：
{existing_memories}

请输出 JSON 格式的决策（不要添加其他文字）：
- action: "ADD"（新记忆是全新信息，应该新增）/ "UPDATE"（新记忆是对某条已有记忆的更新或补充）/ "DELETE"（新记忆表明某条已有记忆已过时）/ "NOOP"（新记忆与已有记忆完全重复，无需操作）
- target_index: 当 action 为 UPDATE 或 DELETE 时，指明目标记忆的索引编号
- reason: 简短说明决策理由

输出格式示例：
{"action": "UPDATE", "target_index": 0, "reason": "新记忆更新了用户的技术栈版本"}

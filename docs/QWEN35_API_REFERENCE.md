# Qwen 3.5 API Reference

> Source: https://unsloth.ai/docs/models/qwen3.5  
> Date: 2026-06-12

## 架构关键发现

Qwen 3.5 的 "思考/非思考" 模式不是通过 `reasoning_format` 参数控制的，而是通过 **聊天模板的参数** 控制：

```bash
# 启动 llama-server 时传入：
--chat-template-kwargs '{"enable_thinking": false}'   # 关闭思考
--chat-template-kwargs '{"enable_thinking": true}'     # 开启思考
```

这是 **服务端级别** 的配置，而不是每个请求的 API 参数。

## 模型尺寸与默认思考模式

| 模型尺寸 | 思考默认状态 |
|----------|-------------|
| 0.8B, 2B, 4B, 9B (Small 系列) | **默认关闭** (需显式传 `enable_thinking:true`) |
| 35B-A3B, 27B, 122B-A10B, 397B-A17B | **默认开启** |

## API 端点

```
http://127.0.0.1:<port>/v1
```

标准 OpenAI 兼容 API。认证用 dummy key: `sk-no-key-required`

## 完整参数表

### 采样参数（每请求可传）

| 参数 | 类型 | 思考模式默认值 | 非思考模式默认值 | 说明 |
|------|------|--------------|----------------|------|
| `temperature` | float | 1.0 | 0.7 | 0.0–2.0 |
| `top_p` | float | 0.95 | 0.8 | Nucleus sampling |
| `top_k` | int | 20 | 20 | Top-k sampling |
| `min_p` | float | 0.0 | 0.0 | 最小概率阈值 |
| `presence_penalty` | float | 1.5 | 1.5 | -2.0–2.0 |
| `repetition_penalty` | float | 1.0 | 1.0 | 重复惩罚 |
| `max_tokens` / `n_predict` | int | (服务端默认) | (服务端默认) | 最大生成 token 数 |
| `max_completion_tokens` | int | (服务端默认) | (服务端默认) | 同 max_tokens |
| `stream` | bool | — | — | 流式输出 |
| `seed` | int | — | — | 随机种子 |

### 精确编程模式（思考）默认值

| 参数 | 值 |
|------|-----|
| `temperature` | 0.6 |
| `top_p` | 0.95 |
| `top_k` | 20 |
| `min_p` | 0.0 |
| `presence_penalty` | 0.0 |

### 推理任务（非思考）默认值

| 参数 | 值 |
|------|-----|
| `temperature` | 1.0 |
| `top_p` | 0.95 |
| `top_k` | 20 |
| `min_p` | 0.0 |
| `presence_penalty` | 1.5 |

## 思考控制机制

### 核心概念

```
enable_thinking (聊天模板层面)
    ├── true  → 模型生成 <think>...</think> + 实际回复
    ├── false → 模型只生成实际回复，无思考
    └── 注入方式: llama-server --chat-template-kwargs '{"enable_thinking": false}'
```

### 响应格式

**思考模式时：**
```json
{
  "choices": [{
    "message": {
      "role": "assistant",
      "content": "Hello! How can I help you?",
      "reasoning_content": "Okay, the user said Hi. This is a simple greeting..."
    }
  }]
}
```

**非思考模式时：**
```json
{
  "choices": [{
    "message": {
      "role": "assistant",
      "content": "Hello! How can I help you?"
    }
  }]
}
```

### 注意

- `reasoning_format: "deepseek"` 是 llama.cpp 的**解析格式**，控制 `<think>` 标签如何被解析
- `enable_thinking` 是聊天模板的**行为控制**，决定模型是否生成思考
- 两者是不同层面的东西，不能混用

## 服务端启动参数

```bash
llama-server \
  --model model.gguf \
  --host 127.0.0.1 \
  --port 11435 \
  --n-ctx 4096 \
  --threads 8 \
  --chat-template-kwargs '{"enable_thinking": false}' \
  --alias "my-model"
```

- `--n-ctx`: 上下文窗口大小 (Qwen 3.5 最大 262,144)
- `--threads`: CPU 线程数
- `--chat-template-kwargs`: JSON 对象，传给聊天模板
- `--alias`: 模型别名
- `--cache-type-k bf16 --cache-type-v bf16`: 推荐 KV 缓存格式（避免乱码）

## llama.cpp 后端 API 完整参数

以下是从 llama.cpp server 源码（server-task.cpp, server-common.h）提取的**每请求**支持的所有参数：

### 生成控制
| 参数 | 类型 | 说明 |
|------|------|------|
| `max_tokens` | int | 最大生成 token |
| `n_predict` | int | 同 max_tokens（优先级最高）|
| `max_completion_tokens` | int | 同 max_tokens（优先级第二）|
| `n_keep` | int | 保留的 prompt token 数 |
| `n_discard` | int | 完成后丢弃的 token 数 |
| `stream` | bool | 流式输出 |
| `stream_options` | object | `{"include_usage": true}` |
| `cache_prompt` | bool | 缓存 prompt 用于后续请求 |
| `timings_per_token` | bool | 每 token 计时 |
| `t_max_predict_ms` | int | 预测阶段超时（毫秒）|

### 采样参数
| 参数 | 类型 | 说明 |
|------|------|------|
| `temperature` | float | 温度 |
| `top_k` | int | Top-K |
| `top_p` | float | Top-P |
| `min_p` | float | Min-P |
| `top_n_sigma` | float | Top-N-Sigma |
| `typical_p` | float | Typical-P |
| `xtc_probability` | float | XTC 概率 |
| `xtc_threshold` | float | XTC 阈值 |
| `repeat_last_n` | int | 重复惩罚窗口 |
| `repeat_penalty` | float | 重复惩罚 |
| `presence_penalty` | float | 存在惩罚 |
| `frequency_penalty` | float | 频率惩罚 |
| `dry_multiplier` | float | DRY 乘数 |
| `dry_base` | float | DRY 基数 |
| `dry_allowed_length` | int | DRY 允许长度 |
| `dry_penalty_last_n` | int | DRY 惩罚窗口 |
| `mirostat` | int | Mirostat 模式 |
| `mirostat_tau` | float | Mirostat tau |
| `mirostat_eta` | float | Mirostat eta |
| `seed` | int | 随机种子 |
| `ignore_eos` | bool | 忽略 EOS |
| `stop` | array | 停止词列表 |

### 对话格式参数
| 参数 | 类型 | 说明 |
|------|------|------|
| `reasoning_format` | string | `"none"` / `"deepseek"` / `"deepseek_legacy"` |
| `generation_prompt` | string | 覆盖生成提示词 |

### 推理预算参数
| 参数 | 类型 | 说明 |
|------|------|------|
| `reasoning_budget_tokens` | int | 推理预算 token 数 (-1=禁用) |
| `reasoning_budget_start_tag` | string | 预算开始标记 |
| `reasoning_budget_end_tag` | string | 预算结束标记 |
| `reasoning_budget_message` | string | 预算用尽后的消息 |
| `reasoning_control` | bool | 推理控制 |

### 工具调用参数（OpenAI 格式）
```json
{
  "tools": [{
    "type": "function",
    "function": {
      "name": "...",
      "description": "...",
      "parameters": { "type": "object", "properties": {...} }
    }
  }],
  "tool_choice": "auto"
}
```

## 关键架构结论

1. **`enable_thinking` ≠ `reasoning_format`**  
   - 前者是聊天模板行为（服务器启动时设置）
   - 后者是 think 标签的解析格式（可每请求传）

2. **网关应该透明传递所有参数**  
   - 不注入、不覆盖客户端的采样参数
   - 服务端默认值（enable_thinking、n_ctx 等）在 UI 中配置
   - 每请求参数（temperature、max_tokens 等）完全透传

3. **超时控制位置**  
   - HTTP 层面：`timeout_read` / `timeout_write`（llama.cpp 服务端参数）
   - 推理层面：`t_max_predict_ms`（每请求可传）

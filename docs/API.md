# OllamaBox API Reference

OllamaBox exposes an **Ollama-compatible API** on `http://127.0.0.1:11434`.

The API is provided by llama.cpp's built-in HTTP server, which implements a subset of the Ollama API.

## Endpoints

### GET /health

Check if the server is running.

```bash
curl http://127.0.0.1:11434/health
```

Response:
- `{"status":"ok"}` — Server ready
- `{"status":"loading model"}` — Model loading in progress
- `{"status":"error","message":"..."}` — Error

### GET /v1/models

List loaded models.

```bash
curl http://127.0.0.1:11434/v1/models
```

Response:
```json
{
  "object": "list",
  "data": [
    {
      "id": "model-name.gguf",
      "object": "model",
      "owned_by": "llamacpp",
      "meta": {
        "vocab_type": 2,
        "n_ctx": 2048,
        "n_params": 1543714304,
        "size": 1266788864
      }
    }
  ]
}
```

### POST /v1/chat/completions

Chat completion (OpenAI-compatible format).

```bash
curl http://127.0.0.1:11434/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "model-name.gguf",
    "messages": [
      {"role": "user", "content": "Hello, how are you?"}
    ],
    "max_tokens": 100,
    "temperature": 0.7,
    "stream": false
  }'
```

Parameters:
- `model` (string, required) — Model filename
- `messages` (array, required) — Chat messages with `role` and `content`
- `max_tokens` (int) — Max tokens to generate
- `temperature` (float) — Sampling temperature (0.0-2.0)
- `top_p` (float) — Nucleus sampling
- `stream` (bool) — Enable streaming (SSE)

Response:
```json
{
  "id": "chatcmpl-...",
  "object": "chat.completion",
  "model": "model-name.gguf",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "I'm doing well, thank you!"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 10,
    "completion_tokens": 8,
    "total_tokens": 18
  }
}
```

### POST /v1/completions

Text completion (OpenAI-compatible format).

```bash
curl http://127.0.0.1:11434/v1/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "model-name.gguf",
    "prompt": "The capital of France is",
    "max_tokens": 50,
    "temperature": 0.7
  }'
```

### POST /v1/embeddings

Text embeddings.

```bash
curl http://127.0.0.1:11434/v1/embeddings \
  -H "Content-Type: application/json" \
  -d '{
    "model": "model-name.gguf",
    "input": "Hello world"
  }'
```

### GET /slots

Get server slot status (model loading, cache).

```bash
curl http://127.0.0.1:11434/slots
```

### GET /metrics

Prometheus-compatible metrics (if enabled).

```bash
curl http://127.0.0.1:11434/metrics
```

## Streaming

Set `"stream": true` to receive Server-Sent Events (SSE):

```bash
curl -N http://127.0.0.1:11434/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "model-name.gguf",
    "messages": [{"role": "user", "content": "Tell me a story"}],
    "stream": true
  }'
```

## Compatible Clients

Any Ollama-compatible client works:
- [Chatbox](https://chatboxai.app/) — Desktop/mobile chat UI
- [Open WebUI](https://github.com/open-webui/open-webui)
- [Continue.dev](https://continue.dev/) — IDE plugin
- `curl`, Python `openai` library, etc.

To connect, set the API base URL to `http://127.0.0.1:11434/v1`.

## Model Compatibility

Supports all GGUF format models. Recommended for mobile:
- 1.5B parameters (Q4_K_M or Q6_K) — ~1GB RAM
- 3B parameters (Q4_K_M) — ~2GB RAM (tight)
- Embedding models (e.g., all-MiniLM-L6-v2) — ~100MB

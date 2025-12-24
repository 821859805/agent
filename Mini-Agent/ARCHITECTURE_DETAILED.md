# Mini-Agent 详细架构文档

## 1. 系统概览

Mini-Agent 是一个基于 LLM 的智能 Agent 框架，采用模块化设计，支持多种工具和技能扩展。

## 2. 核心架构组件

### 2.1 Agent 核心引擎

**职责**:
- 管理 Agent 执行循环
- 协调工具调用
- 管理消息历史
- 处理 Token 限制和上下文摘要

**关键方法**:
```python
class Agent:
    def __init__(llm_client, system_prompt, tools, max_steps, workspace_dir)
    def add_user_message(content)
    def run()  # 主执行循环
    def _estimate_tokens()  # Token 计算
    def _summarize_history()  # 上下文摘要
    def _execute_tool_call(tool_call)  # 工具执行
```

**执行流程**:
1. 接收用户输入
2. 检查 Token 限制（超过则摘要）
3. 调用 LLM（传入消息历史 + 工具列表）
4. 解析 LLM 响应
5. 如有工具调用，执行工具并更新历史
6. 重复步骤 3-5，直到完成或达到最大步数

### 2.2 LLM 客户端层

#### 架构设计

```
LLMWrapper (统一接口)
    ↓
LLMClientBase (抽象基类)
    ├── AnthropicClient (Anthropic API 兼容)
    └── OpenAIClient (OpenAI API 兼容)
```

#### 关键特性

**AnthropicClient**:
- 支持交错思维（interleaved thinking）
- 工具调用格式: `tool_use` blocks
- 流式响应支持

**OpenAIClient**:
- 函数调用格式: `function_call`
- 兼容 OpenAI API

**统一接口**:
- `generate(messages, tools)` - 生成响应
- 自动消息格式转换
- 统一错误处理和重试

### 2.3 工具系统

#### 工具层次结构

```
Tool (抽象基类)
├── 基础工具
│   ├── BashTool - Shell 命令执行
│   ├── FileTools - 文件操作（Read/Write/Edit）
│   └── SessionNoteTool - 持久化记忆
├── MCP 工具
│   └── MCPTool - MCP 协议工具包装
└── Skill 工具
    └── SkillTool - Claude Skill 包装
```

#### 工具接口

```python
class Tool:
    @property
    def name() -> str
    @property
    def description() -> str
    @property
    def parameters() -> dict  # JSON Schema
    async def execute(**kwargs) -> ToolResult
```

#### 工具执行流程

1. Agent 接收 LLM 的工具调用请求
2. 根据工具名称查找对应工具
3. 验证参数（根据 JSON Schema）
4. 执行工具（同步或异步）
5. 返回 ToolResult
6. Agent 将结果添加到消息历史

### 2.4 MCP 工具系统

#### 组件

**MCPLoader**:
- 读取 `mcp.json` 配置
- 启动 MCP 服务器进程
- 管理服务器连接生命周期

**MCPServerConnection**:
- 通过 stdio 与服务器通信
- 实现 JSON-RPC 2.0 协议
- 处理初始化、工具列表、工具调用

**MCPTool**:
- 包装 MCP 工具为 Tool 接口
- 处理工具调用和响应

#### 通信协议

```
客户端 → 服务器 (JSON-RPC 2.0)
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "tool_name",
    "arguments": {...}
  }
}

服务器 → 客户端
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "content": [...]
  }
}
```

### 2.5 Skill 工具系统

#### Skill 结构

每个 Skill 包含:
- `SKILL.md` - Skill 定义和说明
- 可选的支持文件（Python 脚本、资源等）

#### Skill 加载流程

1. `SkillLoader` 扫描 `skills/` 目录
2. 解析 `SKILL.md` 文件
3. 提取元数据（名称、描述、允许的工具）
4. 创建 `Skill` 对象
5. `SkillTool` 将 Skill 包装为可执行工具

#### Skill 执行

- Skill 通过系统提示注入到 Agent
- Agent 根据 Skill 描述决定如何使用
- 可以使用允许的工具完成 Skill 任务

### 2.6 配置管理

#### 配置文件结构

**config.yaml**:
```yaml
api_key: "YOUR_API_KEY"
api_base: "https://api.minimaxi.com"
model: "MiniMax-M2"
max_steps: 100
workspace_dir: "./workspace"
token_limit: 80000
```

**mcp.json**:
```json
{
  "mcpServers": {
    "server_name": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-name"],
      "env": {"KEY": "value"},
      "disabled": false
    }
  }
}
```

### 2.7 日志系统

**AgentLogger**:
- 彩色终端输出
- 请求/响应日志
- 工具执行日志
- 错误和警告日志

**日志级别**:
- INFO: 正常操作
- WARNING: 警告信息
- ERROR: 错误信息
- DEBUG: 调试信息（可选）

## 3. 数据流

### 3.1 请求流程

```
用户输入
  ↓
CLI 处理
  ↓
Agent.add_user_message()
  ↓
Agent.run() 循环
  ↓
检查 Token 限制
  ↓ (超限)
执行摘要
  ↓
调用 LLM.generate()
  ↓
LLM 客户端准备请求
  ↓
发送到 MiniMax API
  ↓
接收响应
  ↓
解析响应
  ↓
有工具调用？
  ├─ 是 → 执行工具 → 更新历史 → 继续循环
  └─ 否 → 返回结果 → 结束
```

### 3.2 工具调用流程

```
LLM 返回工具调用
  ↓
Agent._execute_tool_call()
  ↓
查找工具 (tools[name])
  ↓
验证参数
  ↓
执行工具.execute()
  ↓
  ├─ BashTool → 执行 Shell 命令
  ├─ FileTool → 文件操作
  ├─ NoteTool → 读写 Session Note
  ├─ MCPTool → 调用 MCP 服务器
  └─ SkillTool → 使用 Skill 能力
  ↓
返回 ToolResult
  ↓
添加到消息历史
  ↓
继续 Agent 循环
```

### 3.3 MCP 工具调用流程

```
MCPTool.execute()
  ↓
MCPServerConnection.callTool()
  ↓
构建 JSON-RPC 请求
  ↓
通过 stdio 发送到 MCP 服务器
  ↓
等待响应（异步）
  ↓
解析 JSON-RPC 响应
  ↓
提取 content
  ↓
返回 ToolResult
```

## 4. 关键设计决策

### 4.1 为什么使用抽象基类？

- **可扩展性**: 易于添加新的 LLM 客户端或工具
- **统一接口**: 简化 Agent 核心逻辑
- **测试友好**: 可以轻松模拟依赖

### 4.2 为什么支持多种工具类型？

- **基础工具**: 核心功能（文件、Shell）
- **MCP 工具**: 标准化协议，易于集成第三方服务
- **Skill 工具**: 高级能力，专业领域支持

### 4.3 Token 管理策略

- **精确计算**: 使用 tiktoken 准确计算 Token
- **自动摘要**: 超过限制时自动摘要历史
- **可配置**: 通过 token_limit 配置

### 4.4 为什么使用异步？

- **性能**: 工具调用可能涉及 I/O 操作
- **并发**: 支持并发工具调用（未来扩展）
- **MCP 协议**: MCP 服务器通信是异步的

## 5. 扩展点

### 5.1 添加自定义工具

```python
from mini_agent.tools.base import Tool, ToolResult

class MyCustomTool(Tool):
    @property
    def name(self) -> str:
        return "my_tool"
    
    @property
    def description(self) -> str:
        return "Tool description"
    
    @property
    def parameters(self) -> dict:
        return {
            "type": "object",
            "properties": {
                "param1": {"type": "string"}
            }
        }
    
    async def execute(self, **kwargs) -> ToolResult:
        # 实现工具逻辑
        return ToolResult(
            success=True,
            content="Result"
        )
```

### 5.2 添加自定义 LLM 客户端

```python
from mini_agent.llm.base import LLMClientBase
from mini_agent.schema import Message, LLMResponse

class MyLLMClient(LLMClientBase):
    async def generate(self, messages: list[Message], tools=None) -> LLMResponse:
        # 实现 LLM 调用逻辑
        pass
    
    def _prepare_request(self, messages, tools):
        # 准备请求数据
        pass
    
    def _convert_messages(self, messages):
        # 转换消息格式
        pass
```

### 5.3 添加自定义 Skill

1. 在 `mini_agent/skills/` 创建新目录
2. 创建 `SKILL.md` 文件，包含:
   - Skill 名称和描述
   - 使用说明
   - 允许的工具列表
   - 示例
3. Skill 会自动被加载

## 6. 性能考虑

### 6.1 Token 管理

- 使用 tiktoken 精确计算，避免超限
- 自动摘要机制减少 Token 使用
- 可配置的 Token 限制

### 6.2 工具执行

- 异步执行提高并发性
- 超时机制防止工具卡死
- 错误处理确保稳定性

### 6.3 连接管理

- MCP 连接复用
- 自动重连机制
- 资源清理确保无泄漏

## 7. 安全考虑

### 7.1 API Key 管理

- 配置文件不提交到版本控制
- 支持环境变量覆盖

### 7.2 工具执行安全

- Bash 工具支持超时限制
- 文件操作限制在工作空间内
- 工具参数验证

### 7.3 MCP 服务器安全

- 仅连接配置的服务器
- 环境变量隔离
- 进程隔离

## 8. 测试策略

### 8.1 单元测试

- 工具类测试
- LLM 客户端测试
- 工具加载器测试

### 8.2 集成测试

- Agent 端到端测试
- MCP 服务器集成测试
- Skill 加载测试

### 8.3 功能测试

- CLI 交互测试
- 多轮对话测试
- 工具调用测试

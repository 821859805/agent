# Agent 核心架构与大模型交互流程

## Agent 核心架构图

```mermaid
classDiagram
    class Agent {
        -llm: LLMClient
        -tools: dict[str, Tool]
        -messages: list[Message]
        -max_steps: int
        -token_limit: int
        -workspace_dir: Path
        -api_total_tokens: int
        -logger: AgentLogger
        +__init__(llm_client, system_prompt, tools, max_steps, workspace_dir, token_limit)
        +add_user_message(content: str)
        +run() str
        -_estimate_tokens() int
        -_summarize_messages()
        -_create_summary(messages, round_num) str
        -_execute_tool_call(tool_call) ToolResult
    }
    
    class LLMClient {
        -_client: LLMClientBase
        -provider: LLMProvider
        -api_key: str
        -api_base: str
        -model: str
        +generate(messages, tools) LLMResponse
    }
    
    class LLMClientBase {
        <<abstract>>
        +api_key: str
        +api_base: str
        +model: str
        +generate(messages, tools) LLMResponse
        +_prepare_request(messages, tools) dict
        +_convert_messages(messages) tuple
    }
    
    class AnthropicClient {
        -client: AsyncAnthropic
        +generate(messages, tools) LLMResponse
        +_make_api_request(system, messages, tools) Message
        +_convert_tools(tools) list
        +_convert_messages(messages) tuple
    }
    
    class Message {
        +role: str
        +content: str | list
        +thinking: str
        +tool_calls: list[ToolCall]
        +tool_call_id: str
        +name: str
    }
    
    class Tool {
        <<abstract>>
        +name: str
        +description: str
        +parameters: dict
        +execute(**kwargs) ToolResult
    }
    
    class ToolResult {
        +success: bool
        +content: str
        +error: str
    }
    
    class AgentLogger {
        +start_new_run()
        +log_request(messages, tools)
        +log_response(content, thinking, tool_calls, finish_reason)
        +log_tool_result(tool_name, arguments, result)
    }
    
    Agent --> LLMClient : 使用
    Agent --> Tool : 管理
    Agent --> Message : 维护历史
    Agent --> AgentLogger : 记录日志
    LLMClient --> LLMClientBase : 委托
    LLMClientBase <|-- AnthropicClient : 实现
    Agent --> ToolResult : 接收结果
    Tool --> ToolResult : 返回
```

## Agent 内部结构详细图

```mermaid
graph TB
    subgraph "Agent 核心组件"
        A[Agent 实例]
        
        subgraph "状态管理"
            M[消息历史<br/>messages: List[Message]]
            T[工具字典<br/>tools: dict[str, Tool]]
            W[工作空间<br/>workspace_dir: Path]
        end
        
        subgraph "Token 管理"
            TE[Token 估算器<br/>_estimate_tokens]
            TS[摘要触发器<br/>_summarize_messages]
            CS[摘要生成器<br/>_create_summary]
            AT[API Token 计数<br/>api_total_tokens]
        end
        
        subgraph "执行控制"
            EC[执行循环<br/>run method]
            SC[步数计数器<br/>step]
            MS[最大步数<br/>max_steps]
        end
        
        subgraph "工具执行"
            TC[工具调用解析器]
            TE2[工具执行器<br/>_execute_tool_call]
            TR[工具结果处理]
        end
        
        subgraph "日志系统"
            L[AgentLogger]
            LR[请求日志]
            LRES[响应日志]
            LT[工具日志]
        end
    end
    
    A --> M
    A --> T
    A --> W
    A --> TE
    A --> TS
    A --> CS
    A --> AT
    A --> EC
    EC --> SC
    EC --> MS
    EC --> TC
    TC --> TE2
    TE2 --> TR
    A --> L
    L --> LR
    L --> LRES
    L --> LT
    
    TS --> TE
    TS --> CS
    CS --> TE
```

## Agent 与大模型交互完整流程

```mermaid
sequenceDiagram
    participant User as 用户
    participant Agent as Agent 核心
    participant TokenMgr as Token 管理器
    participant LLM as LLM 客户端
    participant API as MiniMax API
    participant Tools as 工具系统
    participant History as 消息历史

    User->>Agent: add_user_message("任务")
    User->>Agent: run()
    
    loop 执行循环 (最多 max_steps 次)
        Agent->>TokenMgr: _estimate_tokens()
        TokenMgr-->>Agent: 当前 Token 数
        
        alt Token 超限
            Agent->>TokenMgr: _summarize_messages()
            TokenMgr->>LLM: 生成摘要请求
            LLM->>API: 摘要 API 调用
            API-->>LLM: 摘要结果
            LLM-->>TokenMgr: 摘要文本
            TokenMgr->>History: 压缩历史消息
            History-->>Agent: 更新后的消息列表
        end
        
        Agent->>LLM: generate(messages, tools)
        
        Note over LLM: 1. 转换消息格式<br/>2. 转换工具格式<br/>3. 准备请求参数
        
        LLM->>API: HTTP POST /v1/messages
        Note over API: 1. 接收消息历史<br/>2. 接收工具列表<br/>3. 模型推理<br/>4. 生成响应
        
        API-->>LLM: 响应 (content, thinking, tool_calls)
        
        Note over LLM: 1. 解析响应<br/>2. 提取 content<br/>3. 提取 thinking<br/>4. 提取 tool_calls<br/>5. 提取 usage
        
        LLM-->>Agent: LLMResponse
        
        Agent->>History: 添加 assistant 消息
        
        alt 有工具调用
            loop 每个工具调用
                Agent->>Tools: 查找工具 (tools[name])
                Tools-->>Agent: Tool 实例
                
                Agent->>Tools: tool.execute(**arguments)
                
                Note over Tools: 执行具体工具逻辑<br/>(Bash/File/MCP/Skill)
                
                Tools-->>Agent: ToolResult
                
                Agent->>History: 添加 tool 消息
            end
            
            Agent->>Agent: step += 1
            Note over Agent: 继续循环，将工具结果<br/>作为上下文继续对话
        else 无工具调用
            Agent-->>User: 返回最终结果
            Note over Agent: 任务完成，退出循环
        end
        
        alt 达到最大步数
            Agent-->>User: 返回超时错误
        end
    end
```

## Agent 执行循环详细流程

```mermaid
flowchart TD
    Start([开始: run方法]) --> Init[初始化: step = 0]
    Init --> Loop{step < max_steps?}
    
    Loop -->|是| CheckToken[检查 Token 限制]
    CheckToken --> Estimate[_estimate_tokens计算Token]
    Estimate --> Compare{Token > limit?}
    
    Compare -->|是| Summarize[触发摘要]
    Summarize --> FindUsers[找到所有用户消息索引]
    FindUsers --> CreateSummary[为每轮执行创建摘要]
    CreateSummary --> CallLLM[调用 LLM 生成摘要]
    CallLLM --> UpdateHistory[更新消息历史]
    UpdateHistory --> PrepareLLM[准备 LLM 调用]
    
    Compare -->|否| PrepareLLM
    PrepareLLM --> LogRequest[记录请求日志]
    LogRequest --> CallLLM2[调用 llm.generate]
    
    CallLLM2 --> ParseResponse[解析 LLM 响应]
    ParseResponse --> LogResponse[记录响应日志]
    LogResponse --> AddAssistantMsg[添加 assistant 消息到历史]
    AddAssistantMsg --> CheckToolCalls{有工具调用?}
    
    CheckToolCalls -->|否| ReturnResult[返回最终结果]
    ReturnResult --> End([结束])
    
    CheckToolCalls -->|是| LoopTools[遍历每个工具调用]
    LoopTools --> FindTool[查找工具 tools[name]]
    FindTool --> ValidateArgs[验证参数]
    ValidateArgs --> ExecuteTool[执行工具 tool.execute]
    ExecuteTool --> LogTool[记录工具执行日志]
    LogTool --> AddToolMsg[添加 tool 消息到历史]
    AddToolMsg --> MoreTools{还有工具?}
    
    MoreTools -->|是| LoopTools
    MoreTools -->|否| IncrementStep[step += 1]
    IncrementStep --> Loop
    
    Loop -->|否| MaxSteps[达到最大步数]
    MaxSteps --> ReturnError[返回错误信息]
    ReturnError --> End
    
    style Start fill:#e1f5ff
    style End fill:#e1f5ff
    style Summarize fill:#fff4e1
    style ExecuteTool fill:#e8f5e9
    style ReturnResult fill:#c8e6c9
```

## 消息历史结构演变

```mermaid
graph LR
    subgraph "初始状态"
        M1[system: 系统提示]
        M2[user: 用户输入1]
    end
    
    subgraph "第一轮执行"
        M3[assistant: 响应1]
        M4[tool: 工具结果1]
        M5[assistant: 响应2]
    end
    
    subgraph "Token 超限后摘要"
        M6[system: 系统提示]
        M7[user: 用户输入1]
        M8[user: 摘要1<br/>Round 1 execution summary]
        M9[user: 用户输入2]
    end
    
    subgraph "继续执行"
        M10[assistant: 响应3]
        M11[tool: 工具结果2]
    end
    
    M1 --> M2
    M2 --> M3
    M3 --> M4
    M4 --> M5
    M5 --> M6
    M6 --> M7
    M7 --> M8
    M8 --> M9
    M9 --> M10
    M10 --> M11
    
    style M8 fill:#fff4e1
    style M6 fill:#e1f5ff
```

## LLM 客户端交互细节

```mermaid
sequenceDiagram
    participant Agent as Agent
    participant LLMWrapper as LLM Wrapper
    participant AnthropicClient as Anthropic Client
    participant SDK as Anthropic SDK
    participant API as MiniMax API

    Agent->>LLMWrapper: generate(messages, tools)
    
    Note over LLMWrapper: 根据 provider 选择客户端
    
    LLMWrapper->>AnthropicClient: generate(messages, tools)
    
    Note over AnthropicClient: 1. 转换消息格式<br/>_convert_messages()
    
    AnthropicClient->>AnthropicClient: _convert_messages(messages)
    Note over AnthropicClient: 提取 system message<br/>转换 user/assistant/tool 消息<br/>处理 thinking blocks<br/>处理 tool_use blocks
    
    AnthropicClient->>AnthropicClient: _convert_tools(tools)
    Note over AnthropicClient: 转换工具为 Anthropic 格式<br/>name, description, input_schema
    
    AnthropicClient->>AnthropicClient: _prepare_request()
    Note over AnthropicClient: 构建请求参数<br/>model, messages, system, tools, max_tokens
    
    AnthropicClient->>SDK: client.messages.create(**params)
    
    Note over SDK: 1. 序列化请求<br/>2. 添加认证头<br/>3. HTTP 请求
    
    SDK->>API: POST /anthropic/v1/messages
    Note over API: Headers:<br/>Authorization: Bearer {api_key}<br/>Content-Type: application/json
    
    Note over API: Body:<br/>{<br/>  "model": "MiniMax-M2",<br/>  "system": "...",<br/>  "messages": [...],<br/>  "tools": [...],<br/>  "max_tokens": 16384<br/>}
    
    API-->>SDK: HTTP 200 Response
    Note over API: {<br/>  "id": "...",<br/>  "content": [...],<br/>  "model": "...",<br/>  "role": "assistant",<br/>  "stop_reason": "...",<br/>  "usage": {...}<br/>}
    
    SDK-->>AnthropicClient: Message 对象
    
    Note over AnthropicClient: 解析响应:<br/>1. 提取 content blocks<br/>2. 提取 thinking<br/>3. 提取 tool_use blocks<br/>4. 提取 usage
    
    AnthropicClient->>AnthropicClient: _parse_response()
    Note over AnthropicClient: 构建 LLMResponse:<br/>- content: 文本内容<br/>- thinking: 思考过程<br/>- tool_calls: 工具调用列表<br/>- finish_reason: 完成原因<br/>- usage: Token 使用量
    
    AnthropicClient-->>LLMWrapper: LLMResponse
    LLMWrapper-->>Agent: LLMResponse
```

## 工具调用详细流程

```mermaid
sequenceDiagram
    participant Agent as Agent
    participant Parser as 响应解析器
    participant ToolRegistry as 工具注册表
    participant Tool as 具体工具
    participant Executor as 工具执行器
    participant History as 消息历史

    Note over Agent: LLM 返回包含 tool_calls
    
    Agent->>Parser: 解析 tool_calls
    Parser-->>Agent: tool_call 列表
    
    loop 每个 tool_call
        Agent->>Agent: 提取 tool_call_id<br/>function_name<br/>arguments
        
        Agent->>ToolRegistry: 查找工具 tools[function_name]
        
        alt 工具不存在
            ToolRegistry-->>Agent: None
            Agent->>Agent: 创建错误 ToolResult
        else 工具存在
            ToolRegistry-->>Agent: Tool 实例
            
            Agent->>Tool: tool.execute(**arguments)
            
            Note over Tool: 根据工具类型执行:<br/>- BashTool: 执行 Shell 命令<br/>- FileTool: 文件操作<br/>- MCPTool: 调用 MCP 服务器<br/>- SkillTool: 执行 Skill 逻辑
            
            Tool->>Executor: 执行具体操作
            Executor-->>Tool: 执行结果
            
            alt 执行成功
                Tool-->>Agent: ToolResult(success=True, content="...")
            else 执行失败
                Tool-->>Agent: ToolResult(success=False, error="...")
            end
        end
        
        Agent->>History: 创建 tool 消息
        Note over History: Message(<br/>  role="tool",<br/>  content=result.content,<br/>  tool_call_id=tool_call_id,<br/>  name=function_name<br/>)
        
        Agent->>History: 添加到消息历史
    end
    
    Note over Agent: 工具执行完成，<br/>继续 Agent 循环
```

## Token 管理机制

```mermaid
flowchart TD
    Start[开始检查] --> Estimate[计算 Token]
    Estimate --> LocalEstimate[本地估算<br/>_estimate_tokens]
    Estimate --> APIReport[API 报告<br/>api_total_tokens]
    
    LocalEstimate --> Compare1{本地 > limit?}
    APIReport --> Compare2{API > limit?}
    
    Compare1 -->|是| Trigger[触发摘要]
    Compare1 -->|否| Check2{检查 API}
    Compare2 -->|是| Trigger
    Compare2 -->|否| Skip[跳过摘要]
    
    Trigger --> FindUsers[找到所有用户消息]
    FindUsers --> Group[按用户消息分组执行过程]
    
    Group --> Loop[遍历每组]
    Loop --> Extract[提取执行消息]
    Extract --> CreatePrompt[创建摘要提示]
    CreatePrompt --> CallLLM[调用 LLM 生成摘要]
    CallLLM --> GetSummary[获取摘要文本]
    GetSummary --> BuildMsg[构建摘要消息]
    BuildMsg --> More{还有组?}
    
    More -->|是| Loop
    More -->|否| Rebuild[重建消息历史]
    
    Rebuild --> Structure[新结构:<br/>system + user1 + summary1 +<br/>user2 + summary2 + ...]
    Structure --> Update[更新 messages]
    Update --> SkipCheck[设置 _skip_next_token_check]
    SkipCheck --> End[完成]
    
    Skip --> End
    
    style Trigger fill:#fff4e1
    style CallLLM fill:#e1f5ff
    style Rebuild fill:#e8f5e9
```

## 数据流图

```mermaid
graph TB
    subgraph "输入"
        UserInput[用户输入]
        Config[配置文件]
    end
    
    subgraph "Agent 处理"
        Agent[Agent 核心]
        History[消息历史]
        Tools[工具集合]
    end
    
    subgraph "LLM 交互"
        LLM[LLM 客户端]
        Request[请求构建]
        Response[响应解析]
    end
    
    subgraph "工具执行"
        ToolCall[工具调用]
        ToolExec[工具执行]
        ToolResult[工具结果]
    end
    
    subgraph "输出"
        FinalResult[最终结果]
        Logs[日志文件]
    end
    
    UserInput --> Agent
    Config --> Agent
    Agent --> History
    Agent --> Tools
    Agent --> LLM
    LLM --> Request
    Request --> LLM
    LLM --> Response
    Response --> Agent
    Agent --> ToolCall
    ToolCall --> ToolExec
    ToolExec --> ToolResult
    ToolResult --> History
    Agent --> FinalResult
    Agent --> Logs
    
    style Agent fill:#4A90E2,color:#fff
    style LLM fill:#50C878,color:#fff
    style ToolExec fill:#FF6B6B,color:#fff
```

## 关键数据结构

### Message 结构
```python
Message(
    role: str,              # "system" | "user" | "assistant" | "tool"
    content: str | list,    # 消息内容
    thinking: str,          # 思考过程（仅 assistant）
    tool_calls: list,       # 工具调用列表（仅 assistant）
    tool_call_id: str,      # 工具调用 ID（仅 tool）
    name: str               # 工具名称（仅 tool）
)
```

### LLMResponse 结构
```python
LLMResponse(
    content: str,           # 文本响应
    thinking: str,          # 思考过程
    tool_calls: list,       # 工具调用列表
    finish_reason: str,     # 完成原因
    usage: TokenUsage       # Token 使用量
)
```

### ToolCall 结构
```python
ToolCall(
    id: str,                # 调用 ID
    function: FunctionCall( # 函数调用
        name: str,          # 工具名称
        arguments: dict     # 参数
    )
)
```

## 关键设计特点

1. **异步执行**: 所有 LLM 调用和工具执行都是异步的
2. **自动摘要**: Token 超限时自动摘要历史，支持长对话
3. **工具抽象**: 统一的 Tool 接口，支持多种工具类型
4. **错误处理**: 完善的异常处理和错误恢复机制
5. **日志记录**: 详细的请求/响应/工具执行日志
6. **步数限制**: 防止无限循环，可配置最大步数
7. **Token 管理**: 精确的 Token 计算和智能摘要策略

# Agent 核心交互流程图

## 简化版：Agent 与大模型交互流程

```mermaid
graph TB
    Start([用户输入任务]) --> AddMsg[添加用户消息到历史]
    AddMsg --> LoopStart{开始执行循环}
    
    LoopStart --> CheckToken[检查 Token 限制]
    CheckToken -->|超限| Summarize[执行摘要压缩历史]
    Summarize --> PrepareLLM
    CheckToken -->|正常| PrepareLLM[准备 LLM 调用]
    
    PrepareLLM --> BuildRequest[构建请求:<br/>消息历史 + 工具列表]
    BuildRequest --> CallLLM[调用 LLM API]
    
    CallLLM --> ParseResponse[解析响应]
    ParseResponse --> ExtractContent[提取内容]
    ParseResponse --> ExtractThinking[提取思考过程]
    ParseResponse --> ExtractToolCalls[提取工具调用]
    
    ExtractContent --> AddToHistory[添加到消息历史]
    ExtractThinking --> AddToHistory
    ExtractToolCalls --> CheckTools{有工具调用?}
    
    CheckTools -->|否| Return[返回最终结果]
    CheckTools -->|是| ExecuteTools[执行工具]
    
    ExecuteTools --> GetResult[获取工具结果]
    GetResult --> AddToolMsg[添加工具结果到历史]
    AddToolMsg --> IncrementStep[步数 +1]
    IncrementStep --> CheckMax{达到最大步数?}
    
    CheckMax -->|否| LoopStart
    CheckMax -->|是| Timeout[返回超时错误]
    
    Return --> End([结束])
    Timeout --> End
    
    style CallLLM fill:#4A90E2,color:#fff
    style ExecuteTools fill:#50C878,color:#fff
    style Summarize fill:#FF6B6B,color:#fff
    style Return fill:#9B59B6,color:#fff
```

## 详细版：完整交互序列

```mermaid
sequenceDiagram
    autonumber
    participant U as 用户
    participant A as Agent
    participant H as 消息历史
    participant T as Token管理器
    participant L as LLM客户端
    participant API as MiniMax API
    participant Tool as 工具系统

    U->>A: 1. 输入任务
    A->>H: 2. 添加用户消息
    
    U->>A: 3. 调用 run()
    
    loop 执行循环 (最多 max_steps 次)
        A->>T: 4. 检查 Token
        T-->>A: Token 数量
        
        alt Token 超限
            A->>T: 5a. 触发摘要
            T->>L: 5b. 生成摘要请求
            L->>API: 5c. API 调用
            API-->>L: 5d. 摘要结果
            L-->>T: 5e. 摘要文本
            T->>H: 5f. 压缩历史
        end
        
        A->>H: 6. 获取消息历史
        H-->>A: 消息列表
        
        A->>A: 7. 准备工具列表
        A->>L: 8. generate(messages, tools)
        
        Note over L: 8a. 转换消息格式<br/>8b. 转换工具格式<br/>8c. 构建请求
        
        L->>API: 9. POST /anthropic/v1/messages
        Note over API: 9a. 接收消息历史<br/>9b. 接收工具定义<br/>9c. 模型推理<br/>9d. 生成响应
        
        API-->>L: 10. 返回响应
        Note over API: {<br/>  content: "文本",<br/>  thinking: "思考",<br/>  tool_calls: [...],<br/>  usage: {...}<br/>}
        
        L->>L: 11. 解析响应
        L-->>A: 12. LLMResponse
        
        A->>H: 13. 添加 assistant 消息
        
        alt 有工具调用
            loop 每个工具调用
                A->>Tool: 14. 查找工具
                Tool-->>A: Tool 实例
                
                A->>Tool: 15. execute(arguments)
                Note over Tool: 执行具体逻辑
                Tool-->>A: 16. ToolResult
                
                A->>H: 17. 添加 tool 消息
            end
            
            A->>A: 18. step += 1
            Note over A: 继续循环
        else 无工具调用
            A-->>U: 19. 返回结果
        end
    end
```

## Agent 核心组件交互

```mermaid
graph LR
    subgraph "Agent 核心"
        A[Agent Engine]
        H[Message History]
        T[Tool Registry]
        TM[Token Manager]
    end
    
    subgraph "LLM 层"
        W[LLM Wrapper]
        C[Anthropic Client]
    end
    
    subgraph "工具层"
        BT[Bash Tool]
        FT[File Tool]
        MT[MCP Tool]
        ST[Skill Tool]
    end
    
    subgraph "外部"
        API[MiniMax API]
    end
    
    A --> H
    A --> T
    A --> TM
    A --> W
    W --> C
    C --> API
    
    T --> BT
    T --> FT
    T --> MT
    T --> ST
    
    A -.->|调用| BT
    A -.->|调用| FT
    A -.->|调用| MT
    A -.->|调用| ST
    
    style A fill:#4A90E2,color:#fff
    style W fill:#50C878,color:#fff
    style T fill:#FF6B6B,color:#fff
```

## 消息流转过程

```mermaid
graph TB
    subgraph "第 1 轮"
        U1[User: 任务1]
        A1[Assistant: 响应1]
        TC1[Tool Call: 工具1]
        TR1[Tool Result: 结果1]
        A2[Assistant: 响应2]
    end
    
    subgraph "Token 超限"
        SUM[Summary: 摘要第1轮]
    end
    
    subgraph "第 2 轮"
        U2[User: 任务2]
        A3[Assistant: 响应3]
        TC2[Tool Call: 工具2]
        TR2[Tool Result: 结果2]
    end
    
    U1 --> A1
    A1 --> TC1
    TC1 --> TR1
    TR1 --> A2
    A2 --> SUM
    SUM --> U2
    U2 --> A3
    A3 --> TC2
    TC2 --> TR2
    
    style SUM fill:#fff4e1
    style U1 fill:#e1f5ff
    style U2 fill:#e1f5ff
```

## LLM 请求构建过程

```mermaid
flowchart TD
    Start[Agent 调用 generate] --> GetMessages[获取消息历史]
    GetMessages --> GetTools[获取工具列表]
    
    GetTools --> ConvertMessages[转换消息格式]
    ConvertMessages --> ExtractSystem[提取 system message]
    ConvertMessages --> ConvertUser[转换 user messages]
    ConvertMessages --> ConvertAssistant[转换 assistant messages<br/>处理 thinking 和 tool_use]
    ConvertMessages --> ConvertTool[转换 tool messages]
    
    GetTools --> ConvertTools[转换工具格式]
    ConvertTools --> BuildToolSchema[构建工具 Schema<br/>name, description, input_schema]
    
    ExtractSystem --> BuildRequest[构建请求参数]
    ConvertUser --> BuildRequest
    ConvertAssistant --> BuildRequest
    ConvertTool --> BuildRequest
    BuildToolSchema --> BuildRequest
    
    BuildRequest --> AddParams[添加参数:<br/>model, max_tokens]
    AddParams --> SendRequest[发送 HTTP 请求]
    
    style ConvertMessages fill:#e1f5ff
    style ConvertTools fill:#fff4e1
    style BuildRequest fill:#e8f5e9
```

## 工具执行决策树

```mermaid
graph TD
    Start[收到工具调用] --> CheckName{工具名称}
    
    CheckName -->|bash| BashTool[执行 Shell 命令]
    CheckName -->|read_file| ReadTool[读取文件]
    CheckName -->|write_file| WriteTool[写入文件]
    CheckName -->|edit_file| EditTool[编辑文件]
    CheckName -->|session_note| NoteTool[读写笔记]
    CheckName -->|mcp_*| MCPTool[调用 MCP 服务器]
    CheckName -->|skill_*| SkillTool[执行 Skill]
    CheckName -->|unknown| Error[返回错误]
    
    BashTool --> ValidateArgs[验证参数]
    ReadTool --> ValidateArgs
    WriteTool --> ValidateArgs
    EditTool --> ValidateArgs
    NoteTool --> ValidateArgs
    MCPTool --> ValidateArgs
    SkillTool --> ValidateArgs
    
    ValidateArgs --> Execute[执行操作]
    Execute --> CheckSuccess{成功?}
    
    CheckSuccess -->|是| Success[返回成功结果]
    CheckSuccess -->|否| Failure[返回错误信息]
    
    Error --> Failure
    
    Success --> AddHistory[添加到历史]
    Failure --> AddHistory
    
    style BashTool fill:#50C878
    style MCPTool fill:#9B59B6
    style SkillTool fill:#F39C12
    style Error fill:#E74C3C
```

## 关键代码路径

### 1. Agent.run() 主循环
```python
async def run(self) -> str:
    step = 0
    while step < self.max_steps:
        # 1. 检查并摘要历史
        await self._summarize_messages()
        
        # 2. 调用 LLM
        response = await self.llm.generate(
            messages=self.messages, 
            tools=list(self.tools.values())
        )
        
        # 3. 添加 assistant 消息
        self.messages.append(assistant_msg)
        
        # 4. 检查工具调用
        if not response.tool_calls:
            return response.content  # 完成
        
        # 5. 执行工具
        for tool_call in response.tool_calls:
            result = await tool.execute(**arguments)
            self.messages.append(tool_msg)
        
        step += 1
```

### 2. LLM 调用链
```python
Agent.run()
  └─> LLMClient.generate()
      └─> AnthropicClient.generate()
          └─> _convert_messages()  # 消息格式转换
          └─> _convert_tools()     # 工具格式转换
          └─> _make_api_request()   # API 调用
              └─> client.messages.create()  # SDK 调用
```

### 3. 工具执行链
```python
Agent.run()
  └─> tool.execute(**arguments)
      ├─> BashTool.execute()      # Shell 命令
      ├─> FileTool.execute()      # 文件操作
      ├─> MCPTool.execute()       # MCP 服务器
      └─> SkillTool.execute()     # Skill 执行
```

## 性能优化点

1. **异步执行**: 所有 I/O 操作都是异步的
2. **Token 摘要**: 自动压缩历史，减少 Token 消耗
3. **工具缓存**: 工具实例复用，避免重复创建
4. **批量处理**: 多个工具调用可以并行执行（未来优化）
5. **日志异步**: 日志写入不阻塞主流程

## 错误处理策略

1. **LLM 调用失败**: 重试机制（RetryConfig）
2. **工具执行失败**: 捕获异常，返回错误 ToolResult
3. **Token 超限**: 自动摘要，避免上下文溢出
4. **步数超限**: 返回超时错误，防止无限循环
5. **工具不存在**: 返回明确的错误信息

# Mini-Agent 架构图（简化版）

## 整体架构

```mermaid
flowchart TD
    Start([用户]) --> CLI[CLI 命令行界面]
    Start --> ACP[ACP 服务器<br/>编辑器集成]
    
    CLI --> Agent[Agent 核心引擎]
    ACP --> Agent
    
    Agent --> LLM[LLM 客户端层]
    Agent --> Tools[工具系统]
    Agent --> Config[配置管理]
    
    LLM --> API[MiniMax API<br/>M2 模型]
    
    Tools --> BaseTools[基础工具<br/>Bash/File/Note]
    Tools --> MCP[MCP 工具系统]
    Tools --> Skills[Skill 工具系统]
    
    MCP --> MCPServers[外部 MCP 服务器]
    Skills --> SkillsRepo[Claude Skills 仓库]
    
    Agent --> Logger[日志系统]
    Agent --> History[消息历史<br/>Token 管理]
    
    style Agent fill:#4A90E2,color:#fff
    style LLM fill:#50C878,color:#fff
    style Tools fill:#FF6B6B,color:#fff
    style MCP fill:#9B59B6,color:#fff
    style Skills fill:#F39C12,color:#fff
```

## 核心组件层次结构

```mermaid
graph TD
    subgraph "接口层 Interface Layer"
        I1[CLI]
        I2[ACP Server]
    end
    
    subgraph "核心层 Core Layer"
        C1[Agent Engine]
        C2[Message History]
        C3[Token Manager]
    end
    
    subgraph "服务层 Service Layer"
        S1[LLM Client]
        S2[Tool Manager]
        S3[Config Manager]
        S4[Logger]
    end
    
    subgraph "工具层 Tool Layer"
        T1[Base Tools]
        T2[MCP Tools]
        T3[Skill Tools]
    end
    
    subgraph "数据层 Data Layer"
        D1[Config Files]
        D2[Workspace]
        D3[Session Notes]
    end
    
    I1 --> C1
    I2 --> C1
    C1 --> C2
    C1 --> C3
    C1 --> S1
    C1 --> S2
    C1 --> S3
    C1 --> S4
    S2 --> T1
    S2 --> T2
    S2 --> T3
    C1 --> D1
    C1 --> D2
    T1 --> D2
    T1 --> D3
```

## Agent 执行循环

```mermaid
stateDiagram-v2
    [*] --> 初始化
    初始化 --> 接收用户输入
    接收用户输入 --> 检查Token限制
    检查Token限制 --> 需要摘要: Token超限
    检查Token限制 --> 调用LLM: Token正常
    需要摘要 --> 执行摘要
    执行摘要 --> 调用LLM
    调用LLM --> 解析响应
    解析响应 --> 需要工具调用: 有工具调用
    解析响应 --> 返回结果: 无工具调用
    需要工具调用 --> 执行工具
    执行工具 --> 更新历史
    更新历史 --> 检查步数限制
    检查步数限制 --> 调用LLM: 未超限
    检查步数限制 --> 返回结果: 超限
    返回结果 --> [*]
```

## 工具系统架构

```mermaid
graph LR
    subgraph "工具基类"
        Tool[Tool Interface]
    end
    
    subgraph "基础工具"
        Bash[Bash Tool]
        File[File Tools]
        Note[Note Tool]
    end
    
    subgraph "MCP 工具"
        Loader[MCP Loader]
        Server[Server Connection]
        MCPTool[MCP Tool]
    end
    
    subgraph "Skill 工具"
        SkillLoader[Skill Loader]
        SkillTool[Skill Tool]
        SkillFiles[Skill Files]
    end
    
    Tool --> Bash
    Tool --> File
    Tool --> Note
    Tool --> MCPTool
    Tool --> SkillTool
    
    Loader --> Server
    Server --> MCPTool
    
    SkillLoader --> SkillFiles
    SkillFiles --> SkillTool
```

## LLM 客户端架构

```mermaid
classDiagram
    class LLMClientBase {
        <<abstract>>
        +api_key
        +api_base
        +model
        +generate()
        +_prepare_request()
        +_convert_messages()
    }
    
    class AnthropicClient {
        +generate()
        +_prepare_request()
        +_convert_messages()
        +_handle_streaming()
    }
    
    class OpenAIClient {
        +generate()
        +_prepare_request()
        +_convert_messages()
    }
    
    class LLMWrapper {
        +get_client()
        +generate()
    }
    
    LLMClientBase <|-- AnthropicClient
    LLMClientBase <|-- OpenAIClient
    LLMWrapper --> LLMClientBase
```

## 数据流图

```mermaid
sequenceDiagram
    participant U as 用户
    participant C as CLI
    participant A as Agent
    participant L as LLM Client
    participant T as Tool System
    participant M as MiniMax API

    U->>C: 输入命令
    C->>A: 创建消息
    A->>A: 检查 Token/步数
    A->>L: 发送请求（消息+工具）
    L->>M: API 调用
    M-->>L: 返回响应
    L-->>A: 解析响应
    
    alt 需要工具调用
        A->>T: 执行工具
        T-->>A: 工具结果
        A->>A: 更新历史
        A->>L: 继续对话
    else 完成
        A-->>C: 返回结果
        C-->>U: 显示结果
    end
```

## 文件结构映射

```
mini_agent/
├── cli.py              # CLI 入口
├── agent.py            # Agent 核心
├── config.py           # 配置管理
├── logger.py           # 日志系统
├── retry.py            # 重试机制
│
├── llm/                # LLM 客户端层
│   ├── base.py         # 抽象基类
│   ├── anthropic_client.py
│   ├── openai_client.py
│   └── llm_wrapper.py
│
├── tools/              # 工具系统
│   ├── base.py         # 工具基类
│   ├── bash_tool.py    # Bash 工具
│   ├── file_tools.py   # 文件工具
│   ├── note_tool.py    # Session Note
│   ├── mcp_loader.py   # MCP 加载器
│   ├── skill_loader.py  # Skill 加载器
│   └── skill_tool.py   # Skill 工具
│
├── skills/             # Claude Skills
│   ├── document-skills/
│   ├── canvas-design/
│   ├── webapp-testing/
│   └── ... (15+ skills)
│
├── schema/             # 数据模型
│   └── schema.py
│
├── acp/                # ACP 服务器
│   └── server.py
│
└── config/             # 配置文件
    ├── config.yaml
    ├── mcp.json
    └── system_prompt.md
```

## 关键设计模式

1. **策略模式**: LLM 客户端可插拔
2. **工厂模式**: 工具动态加载
3. **适配器模式**: MCP/Skill 工具适配
4. **观察者模式**: 日志和回调机制
5. **模板方法**: Agent 执行循环

## 扩展指南

### 添加新工具
```python
class MyTool(Tool):
    @property
    def name(self) -> str:
        return "my_tool"
    
    async def execute(self, **kwargs) -> ToolResult:
        # 实现工具逻辑
        pass
```

### 添加新 LLM 客户端
```python
class MyLLMClient(LLMClientBase):
    async def generate(self, messages, tools):
        # 实现 LLM 调用
        pass
```

### 添加新 Skill
1. 在 `skills/` 目录创建新文件夹
2. 添加 `SKILL.md` 文件
3. 使用 `SkillLoader` 自动加载

### 集成新 MCP 服务器
在 `config/mcp.json` 中添加配置：
```json
{
  "mcpServers": {
    "my_server": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-my"]
    }
  }
}
```

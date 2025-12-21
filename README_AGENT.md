# Minion Agent Java实现

本项目是根据minion项目的agent逻辑在Java中的完整实现。

## 项目结构

```
src/main/java/com/chatbi/agent/
├── agents/          # Agent类
│   ├── BaseAgent.java      # 基础Agent类
│   └── CodeAgent.java       # 基于代码思考的Agent
├── main/            # 核心组件
│   ├── Brain.java          # 核心推理引擎
│   └── Mind.java           # 心智类
├── types/           # 类型定义
│   ├── AgentState.java     # Agent状态
│   ├── CodeAgentState.java # CodeAgent扩展状态
│   ├── AgentResponse.java  # Agent响应
│   ├── Input.java          # 输入对象
│   └── History.java        # 对话历史
├── tools/           # 工具系统
│   ├── BaseTool.java       # 工具基类接口
│   └── FinalAnswerTool.java # 最终答案工具
├── config/          # 配置类
│   └── AgentConfig.java    # Agent配置
└── example/         # 使用示例
    └── AgentExample.java   # Agent使用示例
```

## 核心组件说明

### 1. BaseAgent（基础Agent类）

`BaseAgent`是所有Agent的基础类，提供以下功能：

- **生命周期管理**：`setup()`初始化，`close()`清理资源
- **工具管理**：支持添加和管理工具
- **状态管理**：使用强类型`AgentState`管理执行状态
- **执行流程**：`runAsync()`执行完整任务，`step()`执行单步
- **多LLM支持**：支持主LLM和多个专用LLM

**使用示例**：

```java
BaseAgent agent = new BaseAgent(chatLanguageModel);
agent.setName("my_agent");
agent.addTool(new FinalAnswerTool());
agent.setup().join();

CompletableFuture<Object> result = agent.runAsync("解释什么是人工智能");
result.thenAccept(answer -> {
    System.out.println("答案: " + answer);
}).join();

agent.close().join();
```

### 2. CodeAgent（基于代码思考的Agent）

`CodeAgent`扩展了`BaseAgent`，提供基于代码的推理能力：

- **基于代码的推理**：使用代码而不是纯文本进行推理
- **自我反思能力**：使用`ThinkingEngine`进行自我反思
- **ReAct循环**：Reason-Act-Observe（推理-行动-观察）循环
- **代码执行**：支持提取和执行代码块

**使用示例**：

```java
CodeAgent agent = new CodeAgent(chatLanguageModel);
agent.addTool(new FinalAnswerTool());
agent.setup().join();

CompletableFuture<String> result = agent.solveProblem("计算1到100的和");
result.thenAccept(answer -> {
    System.out.println("答案: " + answer);
}).join();

agent.close().join();
```

### 3. Brain（核心推理引擎）

`Brain`是Agent系统的核心组件，负责：

- 管理多个LLM提供者（主LLM和专用LLM）
- 管理工具集合
- 管理不同的心智（left_mind、right_mind、hippocampus_mind）
- 处理输入和输出
- 支持流式输出

### 4. Mind（心智类）

每个`Mind`代表一种不同的推理策略：

- **left_mind**：逻辑推理和分析思维
- **right_mind**：创造性和艺术性思维
- **hippocampus_mind**：记忆形成和检索

### 5. 工具系统

所有工具都实现`BaseTool`接口：

```java
public interface BaseTool {
    String getName();
    String getDescription();
    Map<String, Map<String, Object>> getInputs();
    Object forward(Object... args);
    default boolean needsState() { return false; }
}
```

**创建自定义工具**：

```java
public class MyTool implements BaseTool {
    @Override
    public String getName() {
        return "my_tool";
    }
    
    @Override
    public String getDescription() {
        return "我的工具描述";
    }
    
    @Override
    public Map<String, Map<String, Object>> getInputs() {
        // 定义输入参数
        return inputs;
    }
    
    @Override
    public Object forward(Object... args) {
        // 实现工具逻辑
        return result;
    }
}
```

## 配置

在`application.properties`中配置：

```properties
# OpenAI Configuration
openai.api.key=${OPENAI_API_KEY:}
openai.model=gpt-4
openai.temperature=0.7
openai.max-tokens=2000

# Agent Configuration
agent.max-steps=20
agent.auto-compact-enabled=true
agent.auto-compact-threshold=0.92
agent.auto-compact-keep-recent=10
```

## 使用步骤

1. **配置API密钥**：设置`OPENAI_API_KEY`环境变量或在`application.properties`中配置

2. **创建Agent**：
   ```java
   CodeAgent agent = new CodeAgent(chatLanguageModel);
   ```

3. **添加工具**（可选）：
   ```java
   agent.addTool(new FinalAnswerTool());
   ```

4. **初始化Agent**：
   ```java
   agent.setup().join();
   ```

5. **执行任务**：
   ```java
   CompletableFuture<String> result = agent.solveProblem("你的问题");
   ```

6. **清理资源**：
   ```java
   agent.close().join();
   ```

## 与Python Minion项目的对应关系

| Python Minion | Java实现 |
|--------------|---------|
| `BaseAgent` | `com.chatbi.agent.agents.BaseAgent` |
| `CodeAgent` | `com.chatbi.agent.agents.CodeAgent` |
| `Brain` | `com.chatbi.agent.main.Brain` |
| `Mind` | `com.chatbi.agent.main.Mind` |
| `AgentState` | `com.chatbi.agent.types.AgentState` |
| `CodeAgentState` | `com.chatbi.agent.types.CodeAgentState` |
| `BaseTool` | `com.chatbi.agent.tools.BaseTool` |
| `Input` | `com.chatbi.agent.types.Input` |
| `AgentResponse` | `com.chatbi.agent.types.AgentResponse` |

## 主要特性

✅ **完整的Agent生命周期管理**  
✅ **工具系统支持**  
✅ **状态管理**  
✅ **多LLM支持**  
✅ **基于代码的推理（CodeAgent）**  
✅ **自我反思能力**  
✅ **LangChain4j集成**  
✅ **Spring Boot集成**  

## 注意事项

1. **Python执行器**：当前版本中Python代码执行器部分标记为TODO，需要后续实现
2. **流式输出**：流式输出功能已定义接口，具体实现需要根据LangChain4j的流式API完善
3. **工具集支持**：工具集（MCP、UTCP等）的支持已预留接口，需要后续实现
4. **记忆系统**：记忆系统（mem0）的集成需要后续实现

## 后续改进方向

- [ ] 实现Python代码执行器
- [ ] 完善流式输出功能
- [ ] 实现工具集支持（MCP、UTCP）
- [ ] 集成记忆系统（mem0）
- [ ] 添加更多工具示例
- [ ] 完善错误处理和重试机制
- [ ] 添加单元测试

## 许可证

本项目遵循与minion项目相同的许可证。


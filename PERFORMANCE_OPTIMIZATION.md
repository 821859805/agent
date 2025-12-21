# Agent性能优化指南

## 问题分析

`basicExample`执行慢的主要原因：

### 1. **runComplete方法的循环问题（已修复）**
- **问题**：原实现使用while循环创建链式CompletableFuture，即使任务完成也会继续执行到maxSteps
- **修复**：改为递归实现，支持提前终止，任务完成后立即返回

### 2. **重复获取LLM实例（已优化）**
- **问题**：每次step都调用`getLlmByModelId()`，虽然有缓存但仍有方法调用开销
- **优化**：在Brain中添加LLM实例缓存，避免重复获取

### 3. **没有执行时间监控（已添加）**
- **问题**：无法了解各个阶段的耗时
- **优化**：添加详细的日志记录，包括初始化时间、LLM调用时间、总耗时

## 性能优化建议

### 1. **减少maxSteps**
如果任务简单，可以减少maxSteps避免不必要的循环：

```java
agent.setMaxSteps(5); // 默认是20，对于简单任务可以减少
```

### 2. **使用更快的模型**
对于简单任务，可以使用更快的模型：

```java
CodeAgent agent = new CodeAgent(llmModelFactoryService, "gpt-4o-mini"); // 更快的模型
```

### 3. **复用Agent实例**
不要每次都创建新的Agent，可以复用：

```java
// 创建一次
CodeAgent agent = new CodeAgent(llmModelFactoryService, "qwen3-max");
agent.setup().join();

// 多次使用
agent.solveProblem("问题1").join();
agent.solveProblem("问题2").join();
```

### 4. **异步执行（不阻塞）**
如果不需要等待结果，可以使用异步方式：

```java
agent.solveProblem(problem)
    .thenAccept(answer -> {
        log.info("答案: {}", answer);
    });
// 不调用.join()，继续执行其他代码
```

### 5. **优化提示词**
确保提示词简洁明确，减少LLM处理时间：

```java
// 好的提示词：明确、简洁
String problem = "计算1到100的和";

// 避免：过于复杂或模糊
String problem = "请帮我计算一下从1开始到100结束的所有数字相加的结果，并且要详细说明计算过程";
```

### 6. **检查网络延迟**
如果使用远程LLM服务，网络延迟可能是主要瓶颈：

- 检查LLM服务的响应时间
- 考虑使用本地模型（如Ollama）
- 使用连接池和超时设置

### 7. **监控LLM调用时间**
已添加执行时间监控，查看日志了解各阶段耗时：

```
INFO: LLM call completed in 1234ms
INFO: 解决问题耗时: 1500ms
INFO: 总耗时: 2000ms
```

## 代码改进

### 已实现的优化：

1. ✅ **递归执行替代循环**：支持提前终止
2. ✅ **LLM实例缓存**：避免重复获取
3. ✅ **执行时间监控**：添加详细日志
4. ✅ **错误处理优化**：更好的异常信息

### 未来可优化的点：

1. ⏳ **流式输出**：支持流式返回，提升用户体验
2. ⏳ **并发执行**：对于多步骤任务，可以并发执行独立步骤
3. ⏳ **结果缓存**：对于相同问题，可以缓存结果
4. ⏳ **批量处理**：支持批量处理多个问题

## 性能测试

运行测试并查看日志：

```bash
mvn test -Dtest=AgentExample#basicExample
```

查看日志输出，关注：
- Agent初始化耗时
- LLM调用耗时
- 总耗时

如果某个阶段耗时过长，可以针对性地优化。


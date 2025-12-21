# MCP Agent Example 运行指南

## 📋 前置要求

### 1. 安装依赖

确保已安装项目依赖：

```bash
# 安装项目（开发模式）
pip install -e .

# 或者安装所有可选依赖
pip install -e ".[all]"

# 或者直接安装（如果已发布到 PyPI）
pip install minionx[all]
```

**注意**: MCP 相关依赖已包含在基础依赖中（`mcp` 包），但 MCP filesystem server 需要 Node.js。

### 2. 安装 Node.js 和 npm

MCP filesystem toolset 需要 Node.js 来运行 MCP 服务器。请确保已安装：

```bash
# 检查 Node.js 版本（需要 >= 14）
node --version

# 检查 npm 版本
npm --version
```

如果没有安装，请访问 [Node.js 官网](https://nodejs.org/) 下载安装。

### 3. 配置文件设置

#### 步骤 1: 复制配置文件

```bash
# 复制配置文件模板
cp config/config.yaml.example config/config.yaml
cp config/.env.example config/.env
```

#### 步骤 2: 编辑 `config/config.yaml`

在 `models` 部分添加或修改 `gpt-4o` 配置（示例中使用的是 `gpt-4o`）：

```yaml
models:
  "default":
    api_type: "openai"
    base_url: "${DEFAULT_BASE_URL}"
    api_key: "${DEFAULT_API_KEY}"
    model: "${DEFAULT_MODEL}"
    temperature: 0
  
  "gpt-4o":  # 添加这个配置
    api_type: "openai"
    base_url: "${OPENAI_BASE_URL}"  # 或直接使用 "https://api.openai.com/v1"
    api_key: "${OPENAI_API_KEY}"
    model: "gpt-4o"
    temperature: 0
```

#### 步骤 3: 编辑 `config/.env`

设置环境变量：

```bash
# OpenAI API 配置（如果使用 OpenAI）
OPENAI_API_KEY=sk-your-api-key-here
OPENAI_BASE_URL=https://api.openai.com/v1

# 或者使用其他兼容 OpenAI API 的服务
DEFAULT_API_KEY=sk-your-api-key-here
DEFAULT_BASE_URL=https://your-api-endpoint.com/v1
DEFAULT_MODEL=gpt-4o
```

**注意**: 如果使用其他 LLM 服务（如 DeepSeek、Claude 等），请相应修改配置。

## 🚀 运行示例

### 方法 1: 直接运行文件

```bash
# 从项目根目录运行
python examples/mcp/mcp_agent_example.py

# 或者先进入目录
cd examples/mcp
python mcp_agent_example.py
```

### 方法 2: 以模块方式运行（推荐）

从项目根目录运行：

```bash
# 方式 1: 使用 -m 参数（需要确保 examples 是包）
python -m examples.mcp.mcp_agent_example

# 方式 2: 如果上面的方式不工作，可以设置 PYTHONPATH
# Windows
set PYTHONPATH=%CD% && python -m examples.mcp.mcp_agent_example

# Linux/Mac
PYTHONPATH=. python -m examples.mcp.mcp_agent_example
```

**注意**: 如果 `examples/mcp/` 目录下没有 `__init__.py` 文件，模块方式可能无法工作。可以创建该文件：

```bash
# 创建空的 __init__.py 文件
touch examples/mcp/__init__.py
# Windows: type nul > examples\mcp\__init__.py
```

### 方法 3: 使用 Python 解释器直接执行

```bash
python -c "import asyncio; from examples.mcp.mcp_agent_example import main; asyncio.run(main())"
```

## 🔧 代码说明

### 主要功能

示例代码展示了以下功能：

1. **MCP Filesystem Toolset**: 创建文件系统工具集，允许 Agent 访问文件系统
2. **自定义工具**: 
   - `calc`: 同步数学计算工具
   - `async_func`: 异步处理工具
3. **工具自动转换**: 原始 Python 函数会自动转换为 BaseTool/AsyncBaseTool

### 关键代码片段

```python
# 创建 MCP filesystem toolset
mcp_toolset = await create_filesystem_toolset(
    workspace_paths=[str(Path(__file__).parent.parent.parent)],  # 项目根目录
    name="agent_mcp_filesystem_toolset"
)

# 创建 Agent 并添加工具
agent = await CodeAgent.create(
    llm="gpt-4o",  # 使用配置中的模型名称
    tools=all_tools,  # MCP 工具 + 自定义工具
    name="Enhanced MCP Agent"
)
```

## ⚠️ 常见问题

### 1. MCP Toolset 设置失败

**错误信息**: `❌ MCP toolset setup failed`

**可能原因**:
- Node.js 未安装或版本过低
- npx 命令不可用
- 网络问题导致无法下载 MCP server

**解决方案**:
```bash
# 检查 Node.js
node --version  # 应该 >= 14

# 手动测试 npx
npx --version

# 手动测试 MCP server 下载
npx -y @modelcontextprotocol/server-filesystem --help
```

### 2. LLM 配置错误

**错误信息**: `Model 'gpt-4o' not found in config`

**解决方案**:
- 确保 `config/config.yaml` 中有 `gpt-4o` 的配置
- 确保 `config/.env` 中设置了正确的 API key 和 base_url
- 或者修改代码中的 `llm="gpt-4o"` 为配置中存在的模型名称

### 3. 导入错误

**错误信息**: `ImportError: cannot import name 'IntercodeDataLoader'`

**解决方案**: 
这个问题已经解决，但如果仍然出现，请检查：
- 确保使用的是最新的代码
- 如果使用 `DataLoader`，确保已安装 PyTorch: `pip install torch`

### 4. 权限错误

**错误信息**: 文件系统操作权限被拒绝

**解决方案**:
- 确保 `workspace_paths` 中的路径有适当的访问权限
- 在 Windows 上，确保路径格式正确（使用正斜杠或原始字符串）

## 📝 修改示例

### 使用不同的 LLM

修改代码中的模型名称：

```python
agent = await CodeAgent.create(
    llm="deepseek-chat",  # 改为你配置的模型名称
    tools=all_tools,
    name="Enhanced MCP Agent"
)
```

### 使用不同的工作空间路径

```python
workspace_paths = [
    "/path/to/workspace1",
    "/path/to/workspace2"
]
mcp_toolset = await create_filesystem_toolset(
    workspace_paths=workspace_paths,
    name="agent_mcp_filesystem_toolset"
)
```

### 添加 StreamableHTTP Toolset

取消注释相关代码并配置：

```python
http_toolset = await create_streamable_http_toolset(
    url="http://localhost:8080/mcp",
    headers={"Authorization": "Bearer your-token"},
    timeout=30.0,
    name="http_toolset"
)
mcp_tools = mcp_toolset.get_tools() + http_toolset.get_tools()
```

## 🎯 预期输出

成功运行后，你应该看到类似以下的输出：

```
Creating and setting up MCP filesystem toolset...
✅ MCP toolset ready with X tools
🔧 Agent setup complete! Final tool count: Y
📋 Available tools:
  - list_directory: MCP Tool
    List files and directories
  - read_file: MCP Tool
    Read file contents
  ...

🤖 Starting conversation with enhanced MCP agent...

📁 Testing filesystem operations:
[List of files in directory]

🧮 Testing synchronous calc tool:
[Calculation result]

⚡ Testing asynchronous func tool:
[Processing result]
```

## 📚 相关文档

- [MCP Toolset 重构说明](mcp_toolset_refactor.md)
- [StreamableHTTP 指南](mcp_streamable_http_guide.md)
- [CodeAgent 文档](merged_code_agent.md)

## 🔗 相关链接

- [MCP 官方文档](https://modelcontextprotocol.io/)
- [MCP Filesystem Server](https://github.com/modelcontextprotocol/servers/tree/main/src/filesystem)


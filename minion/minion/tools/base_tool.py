#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
@Time    : 2024
@Author  : femto Zheng
@File    : base_tool.py
"""
import warnings
#modified from smolagents

from abc import ABC, abstractmethod
import inspect
import json
import textwrap
from contextlib import contextmanager
from typing import Any, Dict, Optional, Callable, List, Union, Set, TypeVar, get_type_hints
import functools
import ast
import sys

class BaseTool(ABC): # ABC是抽象基类的意思
    """工具基类，定义所有工具的基本接口"""

    # 工具名
    name: str = "base_tool"

    # 工具介绍
    description: str = "基础工具类，所有工具应继承此类"

    # 输入
    inputs: Dict[str, Dict[str, Any]] = {}

    # 输出类型
    output_type: str

    # 输出schema
    output_schema: dict[str, Any] | None = None #not used now

    # 只读
    readonly: bool | None = None

    # 是否需要接收 agent 状态
    needs_state: bool = False  # 是否需要接收agent state
    
    def __init__(self):
        """初始化工具"""
        self.is_initialized = False # 未初始化

    # 对象本身可作为函数调用
    def __call__(self, *args, **kwargs) -> Any:
        """
        调用工具执行，这是工具的主入口
        
        Returns:
            工具执行结果
        """

        # 初始化
        if not self.is_initialized:
            self.setup()
            
        # 处理传入单一字典的情况
        if len(args) == 1 and len(kwargs) == 0 and isinstance(args[0], dict):
            potential_kwargs = args[0]
            if all(key in self.inputs for key in potential_kwargs):
                args = ()
                kwargs = potential_kwargs
                
        return self.forward(*args, **kwargs)
    
    @abstractmethod
    def forward(self, *args, **kwargs) -> Any:
        """
        实际的工具执行逻辑，子类必须实现此方法

        Args:
            *args: 位置参数
            **kwargs: 关键字参数

        Returns:
            工具执行结果
        """
        raise NotImplementedError("工具子类必须实现forward方法")

    def format_for_observation(self, output: Any) -> str:
        """
        Format tool output for LLM observation (when tool call is the last item in code).

        This method can be overridden by tools to provide LLM-friendly formatting
        of their output when used as observations. For example:
        - file_read tool can add line numbers
        - search tool can format results with highlighting
        - calculator tool can show step-by-step computation

        Args:
            output: The raw output from forward() method

        Returns:
            Formatted string suitable for LLM observation

        Default behavior: Convert output to string
        """
        return str(output) if output is not None else ""
    
    def setup(self):
        """
        在首次使用前执行初始化操作
        用于执行耗时的初始化操作（如加载模型）
        """
        self.is_initialized = True
        
    def to_dict(self) -> Dict[str, Any]:
        """
        转换为字典表示
        
        Returns:
            工具的字典表示
        """
        imports = set()
        tool_code = f"""
class {self.__class__.__name__}(BaseTool):
    name = "{self.name}"
    description = "{self.description}"
    inputs = {repr(self.inputs)}
    output_type = "{self.output_type}"
    readonly = {self.readonly}
    
    def __init__(self):
        super().__init__()
        self.is_initialized = True
        
    def forward(self, *args, **kwargs):
        # 实现工具的逻辑
        pass
"""
        from minion.tools.tool_decorator import get_imports
        imports = get_imports(tool_code)
        requirements = {el for el in imports if el not in sys.stdlib_module_names}
        
        return {
            "name": self.name,
            "code": tool_code,
            "requirements": sorted(list(requirements))
        }

class ToolCollection:
    """工具集合，用于管理多个工具"""
    
    def __init__(self, tools: List[BaseTool]):
        self.tools = tools
        
    @classmethod
    def from_directory(cls, directory_path: str) -> 'ToolCollection':
        """从目录加载所有工具"""
        # 实现从目录加载工具的逻辑
        pass


#from smolagents
class Toolset:
    """
    工具收集器加载agent的toolbox中的工具

    工具能被从Hub或mcp中加载 Collections can be loaded from a collection in the Hub or from an MCP server, see:
    - [`Toolset.from_hub`]
    - [`Toolset.from_mcp`]

    For example and usage, see: [`Toolset.from_hub`] and [`Toolset.from_mcp`]
    """

    def __init__(self, tools: list[BaseTool]):
        self.tools = tools

    @classmethod
    async def create(cls, *args, **kwargs):
        """
        异步创建并设置实例

        Args:
            *args: 传递给构造函数的位置参数
            **kwargs: 传递给构造函数的关键字参数

        Returns:
            instance: 已设置完成的实例
        """
        instance = cls(*args, **kwargs)
        await instance.setup()
        return instance

    @classmethod
    def from_hub(
        cls,
        collection_slug: str,
        token: Optional[str] = None,
        trust_remote_code: bool = False,
    ) -> "Toolset":
        """Loads a tool collection from the Hub.

        it adds a collection of tools from all Spaces in the collection to the agent's toolbox

        > [!NOTE]
        > Only Spaces will be fetched, so you can feel free to add models and datasets to your collection if you'd
        > like for this collection to showcase them.

        Args:
            collection_slug (str): The collection slug referencing the collection.
            token (str, *optional*): The authentication token if the collection is private.
            trust_remote_code (bool, *optional*, defaults to False): Whether to trust the remote code.

        Returns:
            Toolset: A tool collection instance loaded with the tools.

        Example:
        ```py
        >>> from smolagents import Toolset, CodeAgent

        >>> image_tool_collection = Toolset.from_hub("huggingface-tools/diffusion-tools-6630bb19a942c2306a2cdb6f")
        >>> agent = CodeAgent(tools=[*image_tool_collection.tools], add_base_tools=True)

        >>> agent.run("Please draw me a picture of rivers and lakes.")
        ```
        """
        _collection = get_collection(collection_slug, token=token)
        _hub_repo_ids = {item.item_id for item in _collection.items if item.item_type == "space"}

        tools = [Tool.from_hub(repo_id, token, trust_remote_code) for repo_id in _hub_repo_ids]

        return cls(tools)

    @classmethod
    @contextmanager
    def from_mcp(
        cls,
        server_parameters: Union["mcp.StdioServerParameters", dict],
        trust_remote_code: bool = False,
        structured_output: Optional[bool] = None,
    ) -> "Toolset":
        """自动从MCP服务器中获取工具

        方法支持Stdio，Streamable HTTP，HTTP+SSE服务器，通过查看server_parameters变量，用于连接MCP服务器

        Note: 系统会生成一个独立的线程，用于运行处理相关任务的异步 I/O 事件循环处理MCP服务器

        Args:
            server_parameters (`mcp.StdioServerParameters` or `dict`):
                Configuration parameters to connect to the MCP server. This can be:

                - An instance of `mcp.StdioServerParameters` for connecting a Stdio MCP server via standard input/output using a subprocess.

                - A `dict` with at least:
                  - "url": URL of the server.
                  - "transport": Transport protocol to use, one of:
                    - "streamable-http": Streamable HTTP transport (default).
                    - "sse": Legacy HTTP+SSE transport (deprecated).
            trust_remote_code (`bool`, *optional*, defaults to `False`):
                是否允许信任并执行由 MCP 服务器上定义的工具所提供的代码，如果信任，设置为True，并理解MCP服务器对本机带来的风险
                如果设置为False，从MCP服务器加载工具会失败
            structured_output (`bool`, *optional*, defaults to `False`):
                是否为MCP工具启动结构化输出，如果设置为True：
                Whether to enable structured output features for MCP tools. If True, enables:
                - 需要MCP工具的输出格式
                - 来自MCP响应的结构化内容的处理
                - 结构化数据解析回退为JSON解析
                如果设置为False，使用原始text-only作为兼容性支持

        Returns:
            Toolset: 一个工具收集器实例

        Example with a Stdio MCP server:
        ```py
        >>> import os
        >>> from smolagents import Toolset, CodeAgent, InferenceClientModel
        >>> from mcp import StdioServerParameters

        >>> model = InferenceClientModel()

        >>> server_parameters = StdioServerParameters(
        >>>     command="uvx",
        >>>     args=["--quiet", "pubmedmcp@0.1.3"],
        >>>     env={"UV_PYTHON": "3.12", **os.environ},
        >>> )

        >>> with Toolset.from_mcp(server_parameters, trust_remote_code=True) as tool_collection:
        >>>     agent = CodeAgent(tools=[*tool_collection.tools], add_base_tools=True, model=model)
        >>>     agent.run("Please find a remedy for hangover.")
        ```

        Example with structured output enabled:
        ```py
        >>> with Toolset.from_mcp(server_parameters, trust_remote_code=True, structured_output=True) as tool_collection:
        >>>     agent = CodeAgent(tools=[*tool_collection.tools], add_base_tools=True, model=model)
        >>>     agent.run("Please find a remedy for hangover.")
        ```

        Example with a Streamable HTTP MCP server:
        ```py
        >>> with Toolset.from_mcp({"url": "http://127.0.0.1:8000/mcp", "transport": "streamable-http"}, trust_remote_code=True) as tool_collection:
        >>>     agent = CodeAgent(tools=[*tool_collection.tools], add_base_tools=True, model=model)
        >>>     agent.run("Please find a remedy for hangover.")
        ```
        """
        # Handle future warning for structured_output default value change
        if structured_output is None:
            warnings.warn(
                "Parameter 'structured_output' was not specified. "
                "Currently it defaults to False, but in version 1.25, the default will change to True. "
                "To suppress this warning, explicitly set structured_output=True (new behavior) or structured_output=False (legacy behavior). "
                "See documentation at https://huggingface.co/docs/smolagents/tutorials/tools#structured-output-and-output-schema-support for more details.",
                FutureWarning,
                stacklevel=2,
            )
            structured_output = False

        try:
            from mcpadapt.core import MCPAdapt
            from mcpadapt.smolagents_adapter import SmolAgentsAdapter
        except ImportError:
            raise ImportError(
                """Please install 'mcp' extra to use Toolset.from_mcp: `pip install 'smolagents[mcp]'`."""
            )
        if isinstance(server_parameters, dict):
            transport = server_parameters.get("transport")
            if transport is None:
                transport = "streamable-http"
                server_parameters["transport"] = transport
            if transport not in {"sse", "streamable-http"}:
                raise ValueError(
                    f"Unsupported transport: {transport}. Supported transports are 'streamable-http' and 'sse'."
                )
        if not trust_remote_code:
            raise ValueError(
                "Loading tools from MCP requires you to acknowledge you trust the MCP server, "
                "as it will execute code on your local machine: pass `trust_remote_code=True`."
            )
        with MCPAdapt(server_parameters, SmolAgentsAdapter(structured_output=structured_output)) as tools:
            yield cls(tools)
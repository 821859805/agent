#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
BaseAgent - Agent 基类

这是所有 Agent 的基础类，提供了完整的 Agent 生命周期管理、工具管理、状态管理、
流式输出、自动上下文压缩等核心功能。

主要功能：
1. 生命周期管理：setup() 初始化，close() 清理资源
2. 工具管理：支持普通工具、工具集（MCP、UTCP等）、状态感知工具
3. 执行流程：run()/run_async() 执行完整任务，step() 执行单步
4. 状态管理：AgentState 强类型状态，支持状态恢复和持久化
5. 流式输出：支持实时流式返回中间结果
6. 自动压缩：当上下文窗口接近上限时自动压缩历史记录
7. 记忆管理：集成 mem0 进行长期记忆存储和检索
8. 多 LLM 支持：支持主 LLM 和多个专用 LLM

使用示例：
    # 创建并初始化 Agent
    agent = await BaseAgent.create(
        name="my_agent",
        llm="gpt-4",
        tools=[my_tool1, my_tool2]
    )
    
    # 执行任务
    result = await agent.run_async("完成某个任务")
    
    # 清理资源
    await agent.close()
"""

from typing import Dict, Any, List, Optional, Tuple, Union
from dataclasses import dataclass, field
import uuid
import asyncio
import logging
import inspect

from ..providers import BaseProvider
from ..tools.base_tool import BaseTool
from ..main.brain import Brain
from ..main.input import Input
from ..main.action_step import ActionStep, StreamChunk, StreamingActionManager
from minion.types.agent_response import AgentResponse
from minion.types.agent_state import AgentState
from minion.types.llm_types import ModelType, create_llm_from_model
from minion.utils.model_price import get_model_context_window, DEFAULT_CONTEXT_WINDOW
from minion.utils.token_counter import num_tokens_from_messages
from minion.types.history import History

logger = logging.getLogger(__name__)


@dataclass
class BaseAgent:
    """
    Agent 基类
    
    定义所有 Agent 的基本接口，支持完整的生命周期管理。
    这是 Minion 框架中所有 Agent 的基础类，提供了统一的接口和通用功能。
    
    核心特性：
    - 工具管理：自动处理普通工具、工具集（MCP/UTCP）、状态感知工具
    - 多 LLM 支持：支持主 LLM 和多个专用 LLM（如 code、math、creative）
    - 状态管理：使用强类型 AgentState 管理执行状态
    - 流式输出：支持实时流式返回执行过程
    - 自动压缩：当上下文接近上限时自动压缩历史记录
    - 记忆管理：集成 mem0 进行长期记忆存储和检索
    
    属性说明：
        name: Agent 名称，用于标识和日志
        tools: 工具列表，可以是 BaseTool 实例或原始函数（会自动转换）
        brain: Brain 实例，负责实际的推理和执行
        llm: 主 LLM 提供者，可以是 BaseProvider、字符串或 ModelType
        llms: 多个专用 LLM 的字典，键为名称（如 "code"、"math"）
        system_prompt: 系统提示词，会传递给 Brain
        state: AgentState 实例，包含当前执行状态
        user_id: 用户 ID，用于记忆管理
        agent_id: Agent ID，自动生成 UUID
        session_id: 会话 ID，自动生成 UUID
        max_steps: 最大执行步数，防止无限循环
        auto_compact_enabled: 是否启用自动压缩
        auto_compact_threshold: 压缩触发阈值（0.92 表示 92%）
        auto_compact_keep_recent: 压缩时保留的最近消息数
        default_context_window: 默认上下文窗口大小（128K tokens）
        compact_model: 用于压缩的模型，None 则使用主 LLM
    """
    
    # ==================== 基础配置 ====================
    name: str = "base_agent"
    """Agent 名称，用于标识和日志记录"""
    
    tools: List[BaseTool] = field(default_factory=list)
    """工具列表，支持 BaseTool 实例或原始函数（会自动转换）"""
    
    brain: Optional[Brain] = None
    """Brain 实例，负责实际的推理和执行逻辑"""
    
    # ==================== LLM 配置 ====================
    llm: Optional[Union[BaseProvider, str, ModelType]] = None
    """
    主 LLM 提供者
    
    可以是：
    - BaseProvider 实例：直接使用
    - 字符串：模型名称（如 "gpt-4"），会自动创建对应的 Provider
    - ModelType：枚举类型，会自动创建对应的 Provider
    """
    
    llms: Optional[Dict[str, Union[BaseProvider, str, ModelType]]] = None
    """
    多个专用 LLM 的字典
    
    键为 LLM 名称（如 "code"、"math"、"creative"），值为 LLM 配置。
    可以在运行时通过 llm 参数指定使用哪个专用 LLM。
    例如：llms={"code": "gpt-4", "math": "claude-3-opus"}
    """
    
    system_prompt: Optional[str] = None
    """系统提示词，会传递给 Brain 用于指导 Agent 行为"""
    
    # ==================== 状态管理 ====================
    state: AgentState = field(default_factory=AgentState)
    """AgentState 实例，包含当前执行状态、历史记录、任务信息等"""
    
    user_id: Optional[str] = None
    """用户 ID，用于记忆管理和多用户场景"""
    
    agent_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    """Agent ID，自动生成 UUID，用于标识不同的 Agent 实例"""
    
    session_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    """会话 ID，自动生成 UUID，用于标识不同的会话"""
    
    max_steps: int = 20
    """最大执行步数，防止 Agent 陷入无限循环"""
    
    # ==================== 自动压缩配置 ====================
    auto_compact_enabled: bool = True
    """是否启用自动压缩功能"""
    
    auto_compact_threshold: float = 0.92
    """
    压缩触发阈值（0.0-1.0）
    
    当历史记录占用的 token 数达到上下文窗口的此百分比时，触发自动压缩。
    例如：0.92 表示当 token 数达到上下文窗口的 92% 时触发压缩。
    """
    
    auto_compact_keep_recent: int = 10
    """
    压缩时保留的最近消息数
    
    压缩历史记录时，会保留最近的 N 条消息不变，只压缩更早的消息。
    这样可以保持最近的上下文完整性。
    """
    
    default_context_window: int = 128000
    """
    默认上下文窗口大小（token 数）
    
    如果无法从 LLM 配置中获取上下文窗口大小，则使用此默认值。
    128000 对应 128K tokens，适用于大多数现代 LLM。
    """
    
    compact_model: Optional[str] = None
    """
    用于压缩的模型名称
    
    如果为 None，则使用主 LLM（self.llm）进行压缩。
    可以指定一个更便宜的模型来执行压缩任务，以节省成本。
    """
    
    # ==================== 内部状态（生命周期管理）====================
    _is_setup: bool = field(default=False, init=False)
    """
    是否已完成初始化设置
    
    用于防止重复调用 setup() 方法。只有 setup() 完成后才能执行任务。
    """
    
    _toolsets: List[Any] = field(default_factory=list, init=False)
    """
    工具集实例列表（MCP、UTCP 等）
    
    工具集是特殊的工具容器，包含多个工具并提供统一的接口。
    在 setup() 时会自动初始化工具集并提取其中的工具。
    """
    
    # ==================== 流式输出管理 ====================
    _streaming_manager: StreamingActionManager = field(default_factory=StreamingActionManager, init=False)
    """
    流式输出管理器
    
    用于管理流式输出过程中的步骤和块（chunks），
    提供步骤开始、添加块、完成步骤等功能。
    """
    
    def __post_init__(self):
        """
        初始化后的处理
        
        在 dataclass 实例化后自动调用，执行必要的初始化操作：
        1. 设置 state 中的 agent 引用
        2. 转换 LLM 配置为 BaseProvider 实例
        3. 从 tools 中提取工具集对象
        """
        # 设置 state 中的 agent 引用，形成双向引用
        if self.state and not self.state.agent:
            self.state.agent = self
            
        # 转换 LLM 配置（字符串/ModelType -> BaseProvider）
        self._convert_llm_configs()
            
        # 从 tools 参数中自动提取工具集对象（MCP、UTCP 等）
        # 工具集对象会被移到 _toolsets 中，在 setup() 时统一初始化
        self._extract_toolsets_from_tools()

    def _convert_llm_configs(self):
        """
        将字符串或 ModelType 类型的 LLM 配置转换为 BaseProvider 实例
        
        这个方法在 __post_init__ 中调用，确保所有 LLM 配置都是 BaseProvider 实例。
        支持转换：
        - 主 LLM（self.llm）
        - 多个专用 LLM（self.llms 字典中的值）
        
        如果已经是 BaseProvider 实例，则保持不变。
        """
        # 转换主 LLM
        if self.llm is not None and not isinstance(self.llm, BaseProvider):
            self.llm = create_llm_from_model(self.llm)
            
        # 转换多个专用 LLM
        if self.llms is not None:
            converted_llms = {}
            for key, model in self.llms.items():
                if isinstance(model, BaseProvider):
                    # 已经是 BaseProvider 实例，直接使用
                    converted_llms[key] = model
                else:
                    # 需要转换：字符串或 ModelType -> BaseProvider
                    converted_llms[key] = create_llm_from_model(model)
            self.llms = converted_llms

    @classmethod
    async def create(cls, *args, **kwargs):
        """
        异步创建并初始化 Agent 实例的便捷方法
        
        这是一个类方法，用于创建并自动完成 setup() 的 Agent 实例。
        相当于：instance = cls(*args, **kwargs); await instance.setup(); return instance
        
        Args:
            *args: 传递给构造函数的位置参数
            **kwargs: 传递给构造函数的关键字参数
            
        Returns:
            BaseAgent: 已初始化完成的 Agent 实例
            
        示例：
            agent = await BaseAgent.create(
                name="my_agent",
                llm="gpt-4",
                tools=[my_tool]
            )
        """
        instance = cls(*args, **kwargs)
        await instance.setup()
        return instance

    def _extract_toolsets_from_tools(self):
        """
        从 tools 参数中提取工具集对象（MCP、UTCP 等）并移到 _toolsets
        
        工具集是特殊的工具容器，包含多个工具并提供统一的接口。
        这个方法会：
        1. 识别 tools 中的工具集对象（通过检查是否有 ensure_setup 和 connection_params/config）
        2. 将工具集对象移到 _toolsets 列表
        3. 保留普通工具在 tools 列表中
        
        工具集会在 setup() 时统一初始化，然后将其中的工具合并到 tools 列表中。
        
        注意：只能在 setup() 之前调用，setup() 后不能再修改工具集。
        
        Raises:
            RuntimeError: 如果 setup() 后尝试修改工具集
        """
        if self._is_setup:
            raise RuntimeError("Cannot modify toolsets after setup")
            
        # 分离工具集对象和普通工具
        toolsets = []
        regular_tools = []
        
        for tool in self.tools:
            # 检查是否是工具集对象（有 ensure_setup 方法和 connection_params/config）
            if (hasattr(tool, 'ensure_setup') and hasattr(tool, 'connection_params')) or \
               (hasattr(tool, 'ensure_setup') and hasattr(tool, 'config')):
                toolsets.append(tool)
            else:
                regular_tools.append(tool)
        
        # 更新 tools 列表，只保留普通工具
        # 工具集中的工具会在 setup() 时添加到 tools 中（如果工具集健康）
        self.tools = regular_tools
        
        # 将工具集对象添加到 _toolsets
        for toolset in toolsets:
            self._toolsets.append(toolset)
            logger.info(f"Auto-detected toolset {getattr(toolset, 'name', 'unnamed')} from tools parameter")
    
    async def setup(self):
        """
        初始化 Agent，设置工具和 Brain
        
        这是 Agent 生命周期中的关键方法，必须在执行任务前调用。
        执行以下操作：
        1. 初始化工具集（MCP、UTCP 等）
        2. 从健康的工具集中提取工具并合并到 tools
        3. 自动转换原始函数为工具
        4. 包装状态感知工具，使其能自动接收 agent state
        5. 初始化 Brain（如果尚未初始化）
        
        注意：
        - 可以安全地多次调用，不会重复初始化
        - 工具集初始化失败不会影响 Agent 运行，只会记录警告
        - 只有健康的工具集才会贡献工具
        
        Raises:
            Exception: 如果 Brain 初始化失败
        """
        # 防止重复初始化
        if self._is_setup:
            return
            
        # 步骤 1: 初始化工具集（MCP、UTCP 等）
        for toolset in self._toolsets:
            try:
                await toolset.ensure_setup()
                if not toolset.is_healthy:
                    logger.warning(f"Toolset {toolset.name} failed to setup: {toolset.setup_error}")
            except Exception as e:
                logger.error(f"Failed to setup toolset {toolset.name}: {e}")

        # 步骤 2: 从健康的工具集中提取工具并合并到 self.tools
        toolset_tools = []
        for toolset in self._toolsets:
            if toolset.is_healthy:
                toolset_tools.extend(toolset.get_tools())
            else:
                logger.warning(f"Skipping unhealthy toolset {toolset.name}")
        
        # 合并工具集工具到 self.tools
        if toolset_tools:
            self.tools.extend(toolset_tools)
            logger.info(f"Added {len(toolset_tools)} toolset tools to agent")

        # 步骤 3: 自动转换原始函数为工具类型
        # 同步函数 -> BaseTool，异步函数 -> AsyncBaseTool
        self._convert_raw_functions_to_tools()
        
        # 步骤 4: 包装状态感知工具，使其能自动接收 agent state
        # 状态感知工具需要访问 agent.state，通过包装器自动传递
        logger.info("About to wrap state-aware tools...")
        self._wrap_state_aware_tools()
        logger.info("Finished wrapping state-aware tools")

        # 步骤 5: 初始化 Brain（如果尚未初始化）
        if self.brain is None:
            # 注意：我们不在这里传递 tools 给 brain
            # 而是在 run/run_async 时传递 agent.tools 给 brain.step
            # 这样可以方便地在运行时刷新工具
            brain_kwargs = {'tools': [], 'state': self.state}
            
            # 处理 LLM（已在 __post_init__ 中转换为 BaseProvider）
            if self.llm is not None:
                brain_kwargs['llm'] = self.llm
                
            if self.llms is not None:
                brain_kwargs['llms'] = self.llms
                    
            self.brain = Brain(**brain_kwargs)
        
        # 标记 Agent 已完成初始化
        self._is_setup = True
    
    async def close(self):
        """
        清理并关闭 Agent，释放所有资源
        
        这是 Agent 生命周期中的清理方法，应该在停止使用 Agent 时调用。
        执行以下操作：
        1. 关闭所有工具集（MCP、UTCP 等），释放连接和资源
        2. 从 tools 中移除工具集相关的工具
        3. 清空 _toolsets 列表
        4. 重置 _is_setup 标志
        
        注意：
        - 可以安全地多次调用
        - 如果 Agent 未初始化，会跳过清理
        - 工具集关闭失败不会影响其他清理操作
        
        示例：
            async with BaseAgent(...) as agent:
                await agent.run_async("任务")
            # 自动调用 close()
        """
        if not self._is_setup:
            logger.warning(f"Agent {self.name} not setup, skipping cleanup")
            return
        
        logger.info(f"Closing agent {self.name}")
        
        # 步骤 1: 关闭所有工具集，释放连接和资源
        for toolset in self._toolsets:
            try:
                await toolset.close()
                logger.info(f"Closed toolset {getattr(toolset, 'name', 'unnamed')}")
            except Exception as e:
                logger.error(f"Error closing toolset {getattr(toolset, 'name', 'unnamed')}: {e}")
        
        # 步骤 2: 从 tools 中移除工具集相关的工具
        # 工具集工具（如 AsyncMcpTool、AsyncUtcpTool）需要随工具集一起清理
        self.tools = [tool for tool in self.tools if not self._is_toolset_tool(tool)]
        
        # 步骤 3: 清空工具集列表
        self._toolsets.clear()
        
        # 步骤 4: 重置初始化标志
        self._is_setup = False
        logger.info(f"Agent {self.name} cleanup completed")
    
    def _convert_raw_functions_to_tools(self):
        """
        自动将原始函数转换为相应的工具类型
        
        这个方法允许用户直接传递普通函数作为工具，系统会自动转换：
        - 同步函数 -> BaseTool（通过 @tool 装饰器）
        - 异步函数 -> AsyncBaseTool（通过 @tool 装饰器）
        
        转换规则：
        - 如果对象是可调用的（callable）但没有工具的 name 和 description 属性，则认为是原始函数
        - 使用统一的 @tool 装饰器进行转换
        - 转换失败的函数会被保留原样，不会影响其他工具
        
        示例：
            def my_function(x: int) -> int:
                return x * 2
            
            agent = BaseAgent(tools=[my_function])  # 会自动转换
        """
        from ..tools.tool_decorator import tool
        from ..tools.async_base_tool import AsyncBaseTool
        
        converted_tools = []
        conversion_count = 0
        
        for item in self.tools:
            # 检查是否是原始函数（不是工具实例）
            # 使用更通用的判断：如果是可调用对象但没有工具的基本属性，则认为是原始函数
            if callable(item) and not (hasattr(item, 'name') and hasattr(item, 'description')):
                try:
                    # 使用统一的tool装饰器进行转换
                    converted_tool = tool(item)
                    converted_tools.append(converted_tool)
                    conversion_count += 1
                    
                    # 记录转换信息
                    tool_type = "AsyncBaseTool" if isinstance(converted_tool, AsyncBaseTool) else "BaseTool"
                    logger.info(f"Auto-converted function '{item.__name__}' to {tool_type}")
                    
                except Exception as e:
                    logger.warning(f"Failed to convert function '{getattr(item, '__name__', str(item))}' to tool: {e}")
                    # 保留原始函数，不进行转换
                    converted_tools.append(item)
            else:
                # 保留已经是工具实例或具有工具属性的项目
                converted_tools.append(item)
        
        # 更新工具列表
        self.tools = converted_tools
        
        if conversion_count > 0:
            logger.info(f"Successfully auto-converted {conversion_count} raw functions to tools")
    
    def _wrap_state_aware_tools(self):
        """
        包装状态感知工具，使其能够自动接收 agent 的 state
        
        状态感知工具（needs_state=True）需要访问 agent.state 来获取上下文信息。
        这个方法会：
        1. 检查工具是否有 needs_state 属性且为 True
        2. 为同步和异步工具分别创建包装器
        3. 包装器会自动将 agent.state 作为参数传递给工具的 forward 方法
        
        包装器实现：
        - 异步工具：创建 async wrapper，将 state 作为关键字参数传递
        - 同步工具：创建 sync wrapper，将 state 作为位置参数传递
        
        注意：
        - 使用闭包捕获 agent 引用，避免循环引用问题
        - 使用 __get__ 方法确保包装器正确绑定到工具实例
        """
        from ..tools.base_tool import BaseTool
        from ..tools.async_base_tool import AsyncBaseTool
        from ..tools.agent_state_aware_tool import AgentStateAwareTool
        import asyncio
        
        wrapped_tools = []
        wrap_count = 0
        
        for tool in self.tools:
            if hasattr(tool, 'needs_state') and tool.needs_state:

                tool_type = type(tool).__name__
                is_async = isinstance(tool, AsyncBaseTool)
                is_base = isinstance(tool, BaseTool)
                logger.debug(f"Wrapping tool {tool.name}: type={tool_type}, is_async={is_async}, is_base={is_base}")

                if isinstance(tool, AsyncBaseTool):
                    # 异步工具包装器 - 使用默认参数来捕获变量
                    def create_async_wrapper(original_forward, agent_ref):
                        async def wrapped_async_forward(self_tool, *args, **kwargs):
                            # 获取agent的state
                            agent_state = getattr(agent_ref, 'state', None)
                            # 将state作为关键字参数传递，这样不依赖于参数位置
                            return await original_forward(*args, state=agent_state, **kwargs)
                        return wrapped_async_forward

                    wrapper = create_async_wrapper(tool.forward, self)
                    tool.forward = wrapper.__get__(tool, type(tool))

                elif isinstance(tool, BaseTool):
                    # 同步工具包装器 - 使用默认参数来捕获变量
                    def create_sync_wrapper(original_forward, agent_ref):
                        def wrapped_sync_forward(self_tool, *args, **kwargs):
                            # 获取agent的state
                            agent_state = getattr(agent_ref, 'state', None)
                            # 将state作为第一个位置参数传递，不传递self_tool
                            return original_forward(*args, state=agent_state, **kwargs)
                        return wrapped_sync_forward

                    wrapper = create_sync_wrapper(tool.forward, self)
                    tool.forward = wrapper.__get__(tool, type(tool))

                wrap_count += 1
                logger.info(f"Successfully wrapped legacy state-aware tool: {tool.name}")
            
            wrapped_tools.append(tool)
        
        # 更新工具列表
        self.tools = wrapped_tools
        
        if wrap_count > 0:
            logger.info(f"Successfully wrapped {wrap_count} state-aware tools")

    def _is_toolset_tool(self, tool: BaseTool) -> bool:
        """
        检查工具是否是工具集工具
        
        工具集工具（如 AsyncMcpTool、AsyncUtcpTool）是由工具集生成的，
        需要随工具集一起清理。
        
        Args:
            tool: 要检查的工具实例
            
        Returns:
            bool: 如果是工具集工具返回 True，否则返回 False
        """
        return tool.__class__.__name__ in ['AsyncMcpTool', 'AsyncUtcpTool']
    
    async def __aenter__(self):
        """
        异步上下文管理器入口
        
        支持使用 async with 语法自动管理 Agent 生命周期：
        - 进入时自动调用 setup()
        - 退出时自动调用 close()
        
        示例：
            async with BaseAgent(...) as agent:
                await agent.run_async("任务")
            # 自动清理资源
        """
        await self.setup()
        return self
    
    async def __aexit__(self, exc_type, exc_val, exc_tb):
        """
        异步上下文管理器出口
        
        无论是否发生异常，都会调用 close() 清理资源。
        
        Args:
            exc_type: 异常类型
            exc_val: 异常值
            exc_tb: 异常追踪信息
        """
        await self.close()
    

    
    @property
    def is_setup(self) -> bool:
        """
        检查 Agent 是否已完成初始化
        
        Returns:
            bool: 如果已完成 setup() 返回 True，否则返回 False
        """
        return self._is_setup
    
    def _ensure_setup(self):
        """
        确保 Agent 已完成初始化
        
        在执行任务前调用，如果未初始化则抛出异常。
        
        Raises:
            RuntimeError: 如果 Agent 未初始化
        """
        if not self._is_setup:
            raise RuntimeError(f"Agent {self.name} not setup. Call setup() first.")

    def run(self, 
           task: Optional[Union[str, Input]] = None,
           state: Optional[AgentState] = None, 
           max_steps: Optional[int] = None,
           reset: bool = False,
           llm: Optional[Union[str, BaseProvider]] = None,
           route: Optional[str] = None,
           **kwargs) -> Any:
        """
        同步接口：运行完整任务
        
        这是 run_async() 的同步包装器，使用 asyncio.run() 执行异步方法。
        适用于同步代码环境，但不推荐在异步环境中使用。
        
        Args:
            task: 任务描述或 Input 对象（state 为 None 时必须提供）
            state: 已有状态，用于恢复中断的执行
            max_steps: 最大步数，None 则使用 self.max_steps
            reset: 如果为 True，在执行前重置 agent 状态
            llm: 可选的 LLM 名称（如 "code", "math", "creative"）或 BaseProvider 实例
            route: 可选的 route 名称，如 "code", "cot", "plan" 等，指定使用哪个 minion
            **kwargs: 附加参数，可包含：
                - tools: 临时工具覆盖
                - 其他参数会传递给 brain.step
                
        Returns:
            Any: 最终任务结果
            
        Raises:
            RuntimeError: 如果 Agent 未初始化
            ValueError: 如果 task 和 state 都为 None
            
        示例：
            result = agent.run("完成某个任务", max_steps=10)
        """
        import asyncio
        return asyncio.run(self.run_async(task=task, state=state, max_steps=max_steps, reset=reset, stream=False, llm=llm, route=route, **kwargs))

    async def run_async(self, 
                       task: Optional[Union[str, Input]] = None,
                       state: Optional[AgentState] = None, 
                       max_steps: Optional[int] = None,
                       reset: bool = False,
                       stream: bool = False,
                       llm: Optional[Union[str, BaseProvider]] = None,
                       route: Optional[str] = None,
                       **kwargs) -> Any:
        """
        异步运行完整任务，自动多步执行直到完成
        
        这是 Agent 的主要执行方法，会：
        1. 初始化或恢复状态
        2. 循环执行步骤直到完成或达到最大步数
        3. 支持流式输出（stream=True）或一次性返回结果（stream=False）
        4. 支持状态恢复，可以从中断的地方继续执行
        
        执行流程：
        - 如果 stream=False：执行所有步骤后返回最终结果
        - 如果 stream=True：返回异步迭代器，逐步 yield 中间结果
        
        Args:
            task: 任务描述或 Input 对象（state 为 None 时必须提供）
            state: 已有状态，用于恢复中断的执行（强类型 AgentState）
            max_steps: 最大步数，None 则使用 self.max_steps
            reset: 如果为 True，在执行前重置 agent 状态
            stream: 若为 True 则使用异步迭代器返回中间结果
            llm: 可选的 LLM 名称（如 "code", "math", "creative"）或 BaseProvider 实例
            route: 可选的 route 名称，如 "code", "cot", "plan" 等，指定使用哪个 minion
            **kwargs: 附加参数，可包含：
                - tools: 临时工具覆盖，会替换 agent.tools
                - 其他参数会传递给 brain.step
                
        Returns:
            Any: 如果 stream=False，返回最终任务结果
            AsyncGenerator: 如果 stream=True，返回异步迭代器，yield StreamChunk
            
        Raises:
            RuntimeError: 如果 Agent 未初始化
            ValueError: 如果 task 和 state 都为 None，或 LLM 未找到
            
        示例：
            # 一次性执行
            result = await agent.run_async("完成某个任务", max_steps=10)
            
            # 流式执行
            async for chunk in agent.run_async("完成某个任务", stream=True):
                print(chunk.content)
        """
        self._ensure_setup()
        
        # 处理参数
        streaming = stream
        
        # 解析可选的LLM参数
        selected_llm_provider = None
        if llm is not None:
            selected_llm_provider = self._resolve_llm(llm)
            if selected_llm_provider is None:
                raise ValueError(f"LLM '{llm}' not found. Available LLMs: {list(self.list_available_llms().keys())}")
        
        # 处理状态初始化或恢复
        if state is None:
            if task is None:
                raise ValueError("Either 'task' or 'state' must be provided")
            # 初始化新状态
            self._init_state_from_task(task, route=route, **kwargs)
        else:
            # 使用已有状态
            self.state = state
                
            # 可选择更新task
            if task is not None:
                if isinstance(task, str):
                    self.state.task = task
                    # 更新input对象的query
                    if self.state.input and hasattr(self.state.input, 'query'):
                        self.state.input.query = task
                else:
                    self.state.task = task.query
                    self.state.input = task
            
            # 设置route（如果提供）
            if route is not None and self.state.input:
                self.state.input.route = route
        
        # 确定最大步数
        max_steps = max_steps or self.max_steps
        
        if stream:
            # 设置 stream_outputs 属性，供 UI 使用
            self.stream_outputs = True
            # 返回异步迭代器
            return self._run_stream(state, max_steps, kwargs, selected_llm_provider)
        else:
            # 清除 stream_outputs 属性
            self.stream_outputs = False
            # 一次性执行完成返回最终结果
            return await self._run_complete(state, max_steps, kwargs, selected_llm_provider)

    async def run_stream(self, 
                        task: Optional[Union[str, Input]] = None,
                        state: Optional[Dict[str, Any]] = None, 
                        max_steps: Optional[int] = None,
                        **kwargs):
        """
        Streaming interface for running the agent.
        
        Args:
            task: Task description or Input object (required if state is None)
            state: Existing state for resuming interrupted execution
            max_steps: Maximum number of steps
            **kwargs: Additional parameters
            
        Returns:
            AsyncGenerator: Stream of responses
        """
        # Force stream=True for this method
        return await self.run_async(task=task, state=state, max_steps=max_steps, stream=True, **kwargs)
            
    async def _run_stream(self, state, max_steps, kwargs, selected_llm_provider=None):
        """返回一个异步迭代器，逐步执行并返回中间结果"""
        step_count = 0
        
        while step_count < max_steps:
            # 开始新的步骤
            input_query = ""
            if state.input and hasattr(state.input, 'query'):
                input_query = str(state.input.query) if state.input.query else ""
            
            action_step = self._streaming_manager.start_step(
                step_type="reasoning",
                input_query=input_query
            )
            #
            # # yield 步骤开始信息
            # yield StreamChunk(
            #     content=f"[STEP {step_count + 1}] Starting reasoning...\n",
            #     chunk_type="step_start",
            #     metadata={"step_number": step_count + 1, "step_id": action_step.step_id}
            # )
            
            # 执行步骤并流式输出
            step_kwargs = kwargs.copy()
            if selected_llm_provider is not None:
                step_kwargs['llm'] = selected_llm_provider
            async for chunk in self._execute_step_stream(state, **step_kwargs):
                action_step.add_chunk(chunk)
                yield chunk
                if hasattr(chunk,'is_final_answer') and chunk.is_final_answer:
                    action_step.is_final_answer = True
            
            # 完成步骤
            result = action_step.to_agent_response()
            
            # 检查是否完成
            if self.is_done(result, state):
                action_step.is_final_answer = True
                self._streaming_manager.complete_current_step(is_final_answer=True)
                
                # yield StreamChunk(
                #     content=f"[FINAL] Task completed!\n",
                #     chunk_type="completion", #interesting, yield completion type
                #     metadata={"final_answer": True}
                # )
                break
            
            # 更新状态，继续下一步
            self._streaming_manager.complete_current_step()
            state = await self.update_state(state, result)
            step_count += 1
            
            # yield StreamChunk(
            #     content=f"\n[STEP {step_count}] Completed. Moving to next step...\n",
            #     chunk_type="step_end",
            #     metadata={"step_number": step_count}
            # )
            
        # 达到最大步数
        if step_count >= max_steps:
            # yield StreamChunk(
            #     content=f"\n[WARNING] Reached maximum steps ({max_steps}). Providing best available answer...\n",
            #     chunk_type="warning"
            # )
            
            try:
                final_answer = await self.provide_final_answer(state)
                yield StreamChunk(
                    content=f"Final answer: {final_answer}\n",
                    chunk_type="final_answer"
                )
            except Exception as e:
                logger.error(f"Failed to provide final answer: {e}")
                raise
    
    async def _execute_step_stream(self, state, **kwargs):
        """执行单个步骤的流式输出"""
        try:
            # 使用agent的tools
            tools = self.tools
            
            # 调用 brain.step 并检查是否返回流式生成器
            result = await self.brain.step(state, tools=tools, stream=True, system_prompt=self.system_prompt, **kwargs)
            
            # 如果 brain.step 返回的是异步生成器，则流式处理
            if inspect.isasyncgen(result):
                async for chunk in result:
                    if isinstance(chunk, str):
                        yield StreamChunk(content=chunk, chunk_type="llm_output")
                    elif isinstance(chunk, StreamChunk):
                        yield chunk
                    else:
                        yield StreamChunk(content=str(chunk), chunk_type="llm_output")
            else:
                # 如果不是流式，直接返回结果
                content = result.answer if hasattr(result, 'answer') else str(result)
                yield StreamChunk(content=content, chunk_type="llm_output")
                
        except Exception as e:
            logger.error(f"Error in step execution: {e}")
            raise
            
    async def _run_complete(self, state, max_steps, kwargs, selected_llm_provider=None):
        """一次性执行所有步骤直到完成，返回最终结果"""
        step_count = 0
        final_result = None
        
        while step_count < max_steps:
            # 传递selected_llm_provider给step方法
            step_kwargs = kwargs.copy()
            if selected_llm_provider is not None:
                step_kwargs['llm'] = selected_llm_provider
            result = await self.step(state, stream=kwargs.get('stream', False), **step_kwargs)
            
            # 检查是否完成
            if self.is_done(result, state):
                return result

            # 更新状态，继续下一步
            state = await self.update_state(state, result)
            step_count += 1

        # 达到最大步数
        if step_count >= max_steps:
            # Try to get the final answer and return it
            try:
                return await self.provide_final_answer(state)
            except Exception as e:
                # If getting the final answer fails, throw the original exception
                logger.error(f"Failed to provide final answer: {e}")
                raise Exception(f"Task execution reached max steps {max_steps} and is still incomplete")
            
        return final_result
    
    async def step(self, state: AgentState, stream: bool = False, llm: Optional[Union[str, BaseProvider]] = None, **kwargs) -> AgentResponse:
        """
        执行单步决策/行动
        
        这是 Agent 的核心执行方法，执行一次推理步骤：
        1. 预处理输入（pre_step）
        2. 解析并设置 LLM（如果提供）
        3. 执行步骤（execute_step，默认委托给 brain.step）
        4. 后处理结果（post_step）
        
        子类可以重写 pre_step、execute_step、post_step 来自定义行为。
        
        Args:
            state: 强类型状态对象，包含 input、history 等必要信息
            stream: 是否流式返回结果
            llm: 可选的 LLM 名称（如 "code", "math", "creative"）或 BaseProvider 实例
            **kwargs: 其他参数，直接传递给 brain.step
            
        Returns:
            AgentResponse: 结构化的响应对象，包含 answer、score、terminated 等
            
        Raises:
            ValueError: 如果 state 中没有 input 对象
            ValueError: 如果指定的 LLM 未找到
        """
        if not state.input:
            raise ValueError("State must contain input object")
            
        input_obj = state.input
        if not isinstance(input_obj, Input):
            # 将字符串转为Input
            input_obj = Input(query=str(input_obj))
            state.input = input_obj
            
        # 预处理输入
        input_obj, kwargs = await self.pre_step(input_obj, kwargs)
        
        # 解析可选的LLM参数
        if llm is not None:
            selected_llm_provider = self._resolve_llm(llm)
            if selected_llm_provider is None:
                raise ValueError(f"LLM '{llm}' not found.")
            kwargs['llm'] = selected_llm_provider
            
        # 执行主要步骤
        result = await self.execute_step(state, stream=stream, **kwargs)
        
        # 确保result是AgentResponse格式
        if not isinstance(result, AgentResponse):
            # 如果是旧的5-tuple格式，转换为AgentResponse
            result = AgentResponse.from_tuple(result)
        
        # 执行后处理操作
        await self.post_step(state.input, result)
        
        return result
    
    async def execute_step(self, state: AgentState, stream: bool = False, **kwargs) -> AgentResponse:
        """
        执行实际的步骤操作，默认委托给 brain 处理
        
        这是 step() 方法的核心实现，负责调用 brain.step() 执行推理。
        子类可以重写此方法以自定义执行逻辑，例如：
        - 添加额外的预处理
        - 修改传递给 brain 的参数
        - 后处理 brain 的返回结果
        
        Args:
            state: 强类型状态对象，包含 input、history 等
            stream: 是否流式返回结果
            **kwargs: 其他参数，会传递给 brain.step
            
        Returns:
            AgentResponse: 结构化的响应对象
            
        注意：
        - 会同步 state 到 brain，使 minion 可以访问 agent 的状态
        - 确保返回 AgentResponse 格式，如果不是则自动转换
        """
        # 使用agent的tools
        tools = self.tools
        
        # 同步state到brain，这样minion可以访问agent的状态
        self.brain.state = state
        
        # 传递强类型状态给brain.step
        result = await self.brain.step(state, tools=tools, stream=stream, system_prompt=self.system_prompt, **kwargs)
        
        # 确保返回AgentResponse格式
        if not isinstance(result, AgentResponse):
            result = AgentResponse.from_tuple(result)
        
        return result
    
    async def pre_step(self, input_data: Input, kwargs: Dict[str, Any]) -> Tuple[Input, Dict[str, Any]]:
        """
        step 执行前的预处理操作
        
        子类可以重写此方法以在执行步骤前进行预处理，例如：
        - 修改输入内容
        - 添加额外的参数
        - 验证输入有效性
        
        Args:
            input_data: 输入数据对象
            kwargs: 其他参数字典
            
        Returns:
            Tuple[Input, Dict[str, Any]]: 处理后的输入数据和参数字典
            
        注意：
        - 默认实现直接返回输入和参数，不做任何修改
        - 子类可以修改输入和参数，然后返回修改后的值
        """
        return input_data, kwargs
    
    async def post_step(self, input_data: Input, result: Any) -> None:
        """
        step 执行后的后处理操作
        
        子类可以重写此方法以在执行步骤后进行后处理，例如：
        - 记录执行日志
        - 更新状态
        - 触发副作用（如发送通知）
        
        Args:
            input_data: 输入数据对象
            result: step 的执行结果，可以是 5-tuple 或 AgentResponse
            
        注意：
        - 默认实现不执行任何操作
        - 此方法不应该修改 result，只应该进行副作用操作
        """
        # 默认实现不执行任何操作
        pass
        
    async def provide_final_answer(self, state: AgentState) -> Any:
        """
        当达到最大步数时尝试提供最终答案
        
        当 Agent 达到最大步数限制但任务尚未完成时，会调用此方法。
        方法会：
        1. 构建一个提示，要求 LLM 基于当前进展提供最佳可能的答案
        2. 执行一步推理获取最终答案
        3. 标记结果为最终答案
        
        Args:
            state: 当前 agent 状态，包含任务和历史记录
            
        Returns:
            Any: 最终答案结果，通常是 AgentResponse 或字符串
            
        Raises:
            Exception: 如果获取最终答案失败
        """
        # 获取任务和历史
        task = state.task or ''
        history = state.history or []
        input_obj = state.input
        
        # 构建提示，要求LLM基于目前进展提供最终答案
        if isinstance(input_obj, Input):
            final_answer_prompt = f"""
You have reached the maximum step limit, but the task is not yet complete.

Original task: {task}

You have executed {len(history)} steps. Based on the current progress, please provide the best possible final answer or conclusion.
Even if the answer is not perfect, provide the best result you can currently derive.

Please provide the answer directly, without explaining why you couldn't complete the entire task.
"""
            # Update the input object's query
            input_obj.query = final_answer_prompt
            
            # Execute one step to get the final answer
            try:
                result = await self.step(state, stream=False)
                
                # Mark the result as final answer
                if hasattr(result, 'terminated') and not result.terminated:
                    # If using AgentResponse format
                    result.terminated = True
                    
                elif isinstance(result, tuple) and len(result) >= 3:
                    # If using 5-tuple format, modify the terminated flag
                    response, score, _, truncated, info = result
                    result = (response, score, True, truncated, info)
                    
                return result
            except Exception as e:
                logger.error(f"获取最终答案失败: {e}")
                # 构造一个基本的回应
                return f"The task could not be completed within the maximum step limit, but {len(history)} steps have been executed. Consider increasing the maximum step limit or simplifying the task."
        
        # If there is no valid input object, return basic information
        return f"The task execution reached the maximum step limit and could not provide a valid final answer."
    
    def _init_state_from_task(self, task: Union[str, Input], route: Optional[str] = None, **kwargs) -> None:
        """
        从任务初始化内部状态
        
        根据任务描述或 Input 对象创建新的 AgentState。
        这是 run_async() 中初始化状态的内部方法。
        
        Args:
            task: 任务描述（字符串）或 Input 对象
            route: 可选的 route 名称，指定使用哪个 minion（如 "code", "cot", "plan"）
            **kwargs: 附加参数，会添加到 state.metadata 中
        """
        # 将任务转换为Input对象
        if isinstance(task, str):
            input_obj = Input(query=task)
            task_str = task
        else:
            input_obj = task
            task_str = task.query
        
        # 设置route（如果提供）
        if route is not None:
            input_obj.route = route
            
        # 初始化强类型状态
        from minion.types.history import History
        self.state = AgentState(
            agent=self,
            input=input_obj,
            history=History(),
            step_count=0,
            task=task_str
        )
        
        # 添加额外的metadata
        self.state.metadata.update(kwargs)

    async def update_state(self, state: AgentState, result: Any) -> AgentState:
        """
        根据步骤结果更新状态
        
        这是 run_async() 中更新状态的核心方法，执行以下操作：
        1. 将步骤结果转换为消息格式并添加到历史记录
        2. 增加步数计数
        3. 更新 input.query 为继续任务的提示
        4. 检查是否需要自动压缩历史记录
        
        Args:
            state: 当前状态（强类型 AgentState）
            result: 步骤执行结果，可以是 5-tuple 或 AgentResponse
            
        Returns:
            AgentState: 更新后的状态
            
        注意：
        - 会自动检查是否需要压缩历史记录
        - 如果启用自动压缩且达到阈值，会调用 _compact_history()
        """
        # Use the passed state
        self.state = state

        # Convert result to message format and add to history
        message_dict = self._convert_result_to_message(result)
        self.state.history.append(message_dict)
        self.state.step_count += 1

        if isinstance(self.state.input, Input):
            self.state.input.query = f"Continue your task: {self.state.task}"

        # Auto compact check
        if self.auto_compact_enabled and self._should_compact(self.state.history):
            self.state.history = await self._compact_history(self.state.history)

        return self.state
    
    def _convert_result_to_message(self, result: Any) -> Dict[str, Any]:
        """
        将步骤结果转换为 OpenAI 消息格式
        
        支持两种结果格式：
        1. AgentResponse 对象：提取 content、score、terminated 等字段
        2. 5-tuple 格式：(response, score, terminated, truncated, info)
        
        转换后的消息格式：
        {
            "role": "assistant",
            "content": "...",
            "metadata": {
                "score": ...,
                "terminated": ...,
                ...
            }
        }
        
        Args:
            result: 步骤执行结果，可以是 5-tuple 或 AgentResponse
            
        Returns:
            Dict[str, Any]: OpenAI 消息格式的字典，包含 role、content 和 metadata
        """
        from minion.types.agent_response import AgentResponse
        
        # 如果是AgentResponse对象
        if isinstance(result, AgentResponse):
            content = result.content or str(result.raw_response) if result.raw_response is not None else ""
            message = {
                "role": "assistant",
                "content": content,
                "metadata": {
                    "score": result.score,
                    "confidence": result.confidence,
                    "terminated": result.terminated,
                    "truncated": result.truncated,
                    "is_final_answer": result.is_final_answer,
                    "timestamp": result.timestamp
                }
            }
            
            # 添加答案信息
            if result.answer is not None:
                message["metadata"]["answer"] = result.answer
            
            # 添加错误信息
            if result.error:
                message["metadata"]["error"] = result.error
                
            # 添加执行统计
            if result.execution_time:
                message["metadata"]["execution_time"] = result.execution_time
            if result.tokens_used:
                message["metadata"]["tokens_used"] = result.tokens_used
            message.pop("metadata")
            return message
        
        # 如果是5-tuple格式
        elif isinstance(result, tuple) and len(result) >= 5:
            response, score, terminated, truncated, info = result
            content = str(response) if response is not None else ""
            
            message = {
                "role": "assistant", 
                "content": content,
                "metadata": {
                    "score": score,
                    "terminated": terminated,
                    "truncated": truncated,
                    "info": info if isinstance(info, dict) else {}
                }
            }
            
            # 从info中提取特殊字段
            if isinstance(info, dict):
                if "answer" in info:
                    message["metadata"]["answer"] = info["answer"]
                if "is_final_answer" in info:
                    message["metadata"]["is_final_answer"] = info["is_final_answer"]
                if "confidence" in info:
                    message["metadata"]["confidence"] = info["confidence"]
                if "error" in info:
                    message["metadata"]["error"] = info["error"]
                    
            return message
        
        # 其他格式，直接转换为字符串内容
        else:
            return {
                "role": "assistant",
                "content": str(result),
                "metadata": {}
            }

    # ============================================
    # Auto Compact Methods（自动压缩方法）
    # ============================================
    # 当上下文窗口接近上限时，自动压缩历史记录以节省 token
    # 压缩策略：保留最近的 N 条消息，将更早的消息压缩为摘要

    def _get_model_name(self) -> Optional[str]:
        """
        从 LLM 提供者获取模型名称
        
        用于确定上下文窗口大小，支持不同的 LLM 配置格式。
        
        Returns:
            Optional[str]: 模型名称，如果无法获取则返回 None
        """
        if self.llm is None:
            return None
        if hasattr(self.llm, 'config') and hasattr(self.llm.config, 'model'):
            return self.llm.config.model
        if hasattr(self.llm, 'model'):
            return self.llm.model
        return None

    def _get_context_window_limit(self) -> int:
        """
        获取当前模型的上下文窗口大小
        
        从模型配置中获取上下文窗口大小，如果无法获取则使用默认值。
        
        Returns:
            int: 上下文窗口限制（token 数）
        """
        model_name = self._get_model_name()
        if model_name:
            context_info = get_model_context_window(model_name)
            return context_info.get("max_input_tokens", self.default_context_window)
        return self.default_context_window

    def _calculate_current_tokens(self, history: History) -> int:
        """
        计算当前历史记录的 token 数量
        
        使用 token_counter 工具计算历史记录占用的 token 数。
        这对于判断是否需要压缩很重要。
        
        Args:
            history: 对话历史记录
            
        Returns:
            int: 历史记录的总 token 数
        """
        if not history or len(history) == 0:
            return 0
        # Convert History to list of message dicts for token counting
        messages = list(history)
        model_name = self._get_model_name() or "gpt-4o"
        return num_tokens_from_messages(messages, model=model_name)

    def _should_compact(self, history: History) -> bool:
        """
        检查是否需要压缩历史记录
        
        根据以下条件判断：
        1. 自动压缩必须启用
        2. 历史记录数量必须大于 auto_compact_keep_recent
        3. 当前 token 数必须达到阈值（auto_compact_threshold * 上下文窗口）
        
        Args:
            history: 对话历史记录
            
        Returns:
            bool: 如果需要压缩返回 True，否则返回 False
        """
        if not self.auto_compact_enabled:
            return False
        if not history or len(history) <= self.auto_compact_keep_recent:
            return False

        current_tokens = self._calculate_current_tokens(history)
        context_limit = self._get_context_window_limit()
        threshold_tokens = int(context_limit * self.auto_compact_threshold)

        if current_tokens >= threshold_tokens:
            logger.info(
                f"Auto compact triggered: {current_tokens} tokens >= {threshold_tokens} "
                f"({self.auto_compact_threshold*100:.0f}% of {context_limit})"
            )
            return True
        return False

    def _get_compact_llm(self) -> BaseProvider:
        """
        获取用于压缩的 LLM
        
        如果指定了 compact_model，则使用该模型；否则使用主 LLM。
        可以使用更便宜的模型来执行压缩任务以节省成本。
        
        Returns:
            BaseProvider: 用于生成摘要的 LLM 提供者
        """
        if self.compact_model:
            return create_llm_from_model(self.compact_model)
        return self.llm

    async def _compact_history(self, history: History) -> History:
        """
        执行压缩：使用 LLM 将旧消息压缩为摘要
        
        压缩策略：
        1. 分离旧消息和最近消息（保留最近的 auto_compact_keep_recent 条）
        2. 分离系统消息（始终保留）
        3. 使用 LLM 将旧消息压缩为摘要
        4. 构建新历史：系统消息 + 摘要 + 最近消息
        
        Args:
            history: 要压缩的对话历史记录
            
        Returns:
            History: 压缩后的历史记录，包含摘要和最近消息
            
        注意：
        - 如果压缩失败，返回原始历史记录
        - 摘要会作为系统消息添加到历史中
        """
        if len(history) <= self.auto_compact_keep_recent:
            return history

        # Split: old messages vs recent messages
        old_messages = list(history)[:-self.auto_compact_keep_recent]
        recent_messages = list(history)[-self.auto_compact_keep_recent:]

        # Separate system messages (always preserve)
        system_messages = [m for m in old_messages if m.get("role") == "system"]
        messages_to_summarize = [m for m in old_messages if m.get("role") != "system"]

        if not messages_to_summarize:
            return history

        # Build summary prompt
        summary_prompt = self._build_compact_prompt(messages_to_summarize)

        # Get LLM for compaction
        compact_llm = self._get_compact_llm()
        if compact_llm is None:
            logger.warning("No LLM available for compaction, skipping")
            return history

        try:
            # Call LLM to generate summary
            summary_messages = [
                {
                    "role": "system",
                    "content": (
                        "You are a conversation history summarizer. Compress the following "
                        "conversation history into a concise summary, preserving:\n"
                        "1. The user's main task and goals\n"
                        "2. Key steps that have been completed\n"
                        "3. Important code snippets or decisions made\n"
                        "4. Problems encountered and their solutions\n\n"
                        "The summary should be detailed enough to allow the conversation to "
                        "continue without losing context."
                    )
                },
                {"role": "user", "content": summary_prompt}
            ]

            # Use the LLM provider's generate method
            if hasattr(compact_llm, 'generate'):
                response = await compact_llm.generate(summary_messages)
                summary_content = response.get("content", "") if isinstance(response, dict) else str(response)
            elif hasattr(compact_llm, 'chat'):
                response = await compact_llm.chat(summary_messages)
                summary_content = response.get("content", "") if isinstance(response, dict) else str(response)
            else:
                logger.warning("LLM provider does not have generate or chat method, skipping compaction")
                return history

            # Build new history: system messages + summary + recent messages
            new_history = History()

            # Add preserved system messages
            for msg in system_messages:
                new_history.append(msg)

            # Add summary as a system message
            summary_message = {
                "role": "system",
                "content": (
                    f"[Conversation History Summary]\n{summary_content}\n"
                    "[End of Summary - Recent messages follow]"
                )
            }
            new_history.append(summary_message)

            # Add recent messages
            for msg in recent_messages:
                new_history.append(msg)

            logger.info(
                f"Compacted history from {len(history)} messages to {len(new_history)} messages "
                f"(summarized {len(messages_to_summarize)} messages)"
            )
            return new_history

        except Exception as e:
            logger.error(f"Failed to compact history: {e}")
            return history

    def _build_compact_prompt(self, messages: List[Dict[str, Any]]) -> str:
        """Build the prompt for summarizing messages.

        Args:
            messages: List of messages to summarize.

        Returns:
            str: The formatted prompt for summarization.
        """
        formatted_messages = []
        for msg in messages:
            role = msg.get("role", "unknown")
            content = msg.get("content", "")
            # Truncate very long messages in the summary request
            if len(content) > 2000:
                content = content[:2000] + "... [truncated]"
            formatted_messages.append(f"[{role.upper()}]: {content}")

        return (
            "Please summarize the following conversation history:\n\n"
            + "\n\n".join(formatted_messages)
            + "\n\nOutput the summary in a clear, structured format."
        )

    async def compact_now(self, state: Optional[AgentState] = None) -> None:
        """Manually trigger compaction.

        Args:
            state: The agent state to compact. Uses self.state if not provided.
        """
        target_state = state or self.state
        if target_state and hasattr(target_state, 'history'):
            target_state.history = await self._compact_history(target_state.history)
            logger.info("Manual compaction completed")

    def is_done(self, result: Any, state: AgentState) -> bool:
        """
        判断任务是否完成
        
        检查步骤结果或状态中的完成标志。支持多种结果格式：
        1. AgentResponse 对象：检查 is_done() 方法、terminated 或 is_final_answer 属性
        2. 5-tuple 格式：检查 terminated 标志（第3个元素）
        3. 状态标志：检查 state.is_final_answer
        
        Args:
            result: 当前步骤结果，可以是 5-tuple 或 AgentResponse
            state: 当前状态（强类型 AgentState）
            
        Returns:
            bool: 如果任务完成返回 True，否则返回 False
        """
        # 检查AgentResponse类型
        if hasattr(result, 'is_done') and callable(result.is_done):
            return result.is_done()
        elif hasattr(result, 'terminated') or hasattr(result, 'is_final_answer'):
            return getattr(result, 'terminated', False) or getattr(result, 'is_final_answer', False)
        
        # 检查5-tuple格式
        if isinstance(result, tuple) and len(result) >= 3:
            # 返回格式为 (response, score, terminated, truncated, info)
            terminated = result[2]
            return terminated
        
        # 检查状态中的final_answer标志
        if state.is_final_answer:
            return True
            
        return False
    
    def finalize(self, result: Any, state: AgentState) -> Any:
        """
        整理最终结果
        
        从步骤结果或状态中提取最终答案。优先级：
        1. state.final_answer_value（如果存在）
        2. result.answer（AgentResponse 格式）
        3. result.raw_response（AgentResponse 格式）
        4. result[0]（5-tuple 格式的 response 部分）
        5. result 字典中的 answer 或 final_answer 字段
        
        Args:
            result: 最后一步结果，可以是 5-tuple 或 AgentResponse
            state: 当前状态（强类型 AgentState）
            
        Returns:
            Any: 最终处理后的结果，通常是字符串或字典
        """
        # 检查状态中的final_answer_value
        if state.final_answer_value is not None:
            return state.final_answer_value
            
        # 检查AgentResponse类型
        if hasattr(result, 'answer') and result.answer is not None:
            return result.answer
        elif hasattr(result, 'raw_response'):
            return result.raw_response
        
        # 检查5-tuple格式
        if isinstance(result, tuple) and len(result) > 0:
            return result[0]  # 返回response部分
        elif isinstance(result, dict):
            return result.get("answer", result.get("final_answer"))  # 兼容旧格式
            
        return result
    
    def _get_builtin_meta_tools(self) -> Dict[str, Any]:
        """获取内置的meta工具"""
        from ..tools.think_tool import ThinkTool
        from ..tools.meta_tools import PlanTool, ReflectionTool
        
        return {
            "think": ThinkTool(),
            "plan": PlanTool(),
            "reflect": ReflectionTool(),
        }
    
    def add_meta_tools(self) -> None:
        """添加内置的meta工具到agent"""
        meta_tools = self._get_builtin_meta_tools()
        for tool_name, tool in meta_tools.items():
            self.add_tool(tool)
            logger.info(f"Added meta tool: {tool_name}")
    
    def add_tool(self, tool: BaseTool) -> None:
        """
        添加工具
        Args:
            tool: 要添加的工具
        """
        self.tools.append(tool)
        # 同时向brain添加工具
        if hasattr(self.brain, 'add_tool'):
            self.brain.add_tool(tool)
    
    def set_primary_llm(self, model: Union[ModelType, str, BaseProvider]) -> None:
        """
        Set the primary LLM for the agent
        
        Args:
            model: Model type, string identifier, or provider instance
        """
        # Convert to BaseProvider if needed
        if isinstance(model, BaseProvider):
            self.llm = model
        else:
            self.llm = create_llm_from_model(model)
            
        # Update brain if already setup
        if self._is_setup and self.brain:
            self.brain.llm = self.llm
    
    def add_specialized_llm(self, name: str, model: Union[ModelType, str, BaseProvider]) -> None:
        """
        Add a specialized LLM for specific tasks
        
        Args:
            name: Name/key for the specialized LLM
            model: Model type, string identifier, or provider instance
        """
        if self.llms is None:
            self.llms = {}
            
        # Convert to BaseProvider if needed
        if isinstance(model, BaseProvider):
            self.llms[name] = model
        else:
            self.llms[name] = create_llm_from_model(model)
        
        # Update brain if already setup
        if self._is_setup and self.brain:
            if not hasattr(self.brain, 'llms') or self.brain.llms is None:
                self.brain.llms = {}
            self.brain.llms[name] = self.llms[name]
    
    def _resolve_llm(self, llm: Union[str, BaseProvider]) -> Optional[BaseProvider]:
        """
        Resolve LLM name or provider to BaseProvider instance
        
        Args:
            llm: LLM name ("primary", "code", "math", etc.) or BaseProvider instance
            
        Returns:
            BaseProvider instance or None if not found
        """
        # If already a BaseProvider instance, return it directly
        if isinstance(llm, BaseProvider):
            return llm
        
        # Otherwise treat as string name
        if llm == "primary":
            return self.llm
        elif self.llms and llm in self.llms:
            return self.llms[llm]
        else:
            return None
    
    def get_tool(self, tool_name: str) -> Optional[BaseTool]:
        """
        获取指定名称的工具
        Args:
            tool_name: 工具名称
        Returns:
            找到的工具实例，如果未找到则返回None
        """
        for tool in self.tools:
            if tool.name == tool_name:
                return tool
        return None
    
    def add_memory(self, messages: Union[str, List[Dict[str, str]]], metadata: Optional[Dict[str, Any]] = None) -> None:
        """
        添加记忆到 brain 的 mem0
        
        将消息添加到长期记忆中，用于后续检索。mem0 会自动处理：
        - 字符串到消息列表的转换
        - 向量化和存储
        - 语义检索
        
        Args:
            messages: 要添加的消息内容
                - 字符串：会被转换为 [{"role": "user", "content": messages}]
                - 列表：每个消息都应该是 {"role": xxx, "content": xxx} 格式
            metadata: 额外的元数据，用于后续过滤和检索
                
        Raises:
            ValueError: 如果 user_id、agent_id 和 session_id 都不存在
            
        注意：
        - 至少需要提供 user_id、agent_id 或 session_id 中的一个
        - 记忆会与这些 ID 关联，用于后续检索
        """
        if self.brain and self.brain.mem:
            # 确保至少有一个必需的ID
            if not any([self.user_id, self.agent_id, self.session_id]):
                raise ValueError("At least one of user_id, agent_id, or session_id (run_id) is required!")
                
            self.brain.mem.add(
                messages=messages,  # mem0会自动处理字符串到消息列表的转换
                user_id=self.user_id,
                agent_id=self.agent_id,
                run_id=self.session_id,
                metadata=metadata
            )
            
    def get_all_memories(self, filter: Optional[Dict[str, Any]] = None) -> List[Any]:
        """
        根据元数据检索记忆
        
        Args:
            filter: 可选的元数据过滤条件，用于筛选记忆

        Returns:
            List[Memory]: 检索到的记忆列表
        """
        if self.brain and self.brain.mem:
            response = self.brain.mem.get_all(
                user_id=self.user_id,
                agent_id=self.agent_id,
                run_id=self.session_id
            )
            
            # 处理返回的数据格式，提取results列表
            memories = []
            if isinstance(response, dict) and 'results' in response:
                # 新的API格式 (v1.1)
                memories = response['results']
            elif isinstance(response, list):
                # 旧的API格式
                memories = response
            else:
                return []
            
            # 如果提供了过滤条件，进行过滤
            if filter:
                filtered_memories = []
                for memory in memories:
                    if "metadata" in memory:
                        # 检查元数据是否满足过滤条件
                        match = True
                        for key, value in filter.items():
                            if key not in memory["metadata"] or memory["metadata"][key] != value:
                                match = False
                                break
                        if match:
                            filtered_memories.append(memory)
                return filtered_memories
            
            return memories
        return []
        
    def search_memories(self, query: str, top_k: int = 5, include_relations: bool = False) -> Union[List[Any], Dict[str, Any]]:
        """
        语义搜索记忆
        
        使用 mem0 的语义搜索功能，根据查询找到相关的记忆。
        搜索会考虑 user_id、agent_id 和 session_id。
        
        Args:
            query: 搜索查询文本
            top_k: 返回结果数量，默认 5
            include_relations: 是否包含关系数据（仅在 mem0 API v1.1 中有效）
            
        Returns:
            Union[List[Any], Dict[str, Any]]:
                - 如果 include_relations=False: 返回搜索结果列表
                - 如果 include_relations=True: 返回包含 'results' 和 'relations' 的字典
                
        注意：
        - 如果 mem0 不可用或搜索失败，返回空列表
        - 兼容不同的 mem0 API 版本（支持 top_k 或 limit 参数）
        """
        if self.brain and self.brain.mem:
            try:
                # 尝试使用top_k参数
                response = self.brain.mem.search(
                    user_id=self.user_id,
                    agent_id=self.agent_id,
                    run_id=self.session_id,
                    query=query,
                    top_k=top_k
                )
            except TypeError:
                # 如果top_k参数不被接受，尝试使用limit参数
                try:
                    response = self.brain.mem.search(
                        user_id=self.user_id,
                        agent_id=self.agent_id,
                        run_id=self.session_id,
                        query=query,
                        limit=top_k
                    )
                except TypeError:
                    # 如果limit参数也不被接受，只使用必需参数
                    response = self.brain.mem.search(
                        user_id=self.user_id,
                        agent_id=self.agent_id,
                        run_id=self.session_id,
                        query=query
                    )
            
            # 处理返回的数据格式
            if isinstance(response, dict):
                if include_relations and 'relations' in response:
                    # 如果需要关系数据，直接返回完整响应
                    return response
                elif 'results' in response:
                    # 否则只返回结果列表
                    return response['results']
            elif isinstance(response, list):
                # 旧的API格式
                return response
                
        return []
        
    def get_conversation_history(self, top_k: int = 10, order: str = "desc") -> List[Dict[str, str]]:
        """
        获取记忆中的对话历史
        Args:
            top_k: 返回的消息数量
            order: 排序方式，"asc" 或 "desc"
        Returns:
            List[Dict[str, str]]: 对话历史列表
        """
        if self.brain and self.brain.mem:
            response = self.brain.mem.get_conversation_history(
                user_id=self.user_id,
                agent_id=self.agent_id,
                run_id=self.session_id,
                top_k=top_k,
                order=order
            )
            
            # 处理返回的数据格式
            if isinstance(response, dict) and 'results' in response:
                # 新的API格式 (v1.1)
                return response['results']
            elif isinstance(response, list):
                # 旧的API格式
                return response
                
        return []
    
    def get_state(self) -> Dict[str, Any]:
        """
        获取当前执行状态
        Returns:
            Dict[str, Any]: 当前状态的副本
        """
        if hasattr(self, 'state') and self.state:
            return self.state.copy()
        else:
            raise ValueError("No current state available. Run agent first.")
    
    def save_state(self, filepath: str) -> None:
        """
        保存状态到文件
        Args:
            filepath: 保存路径
        """
        import pickle
        state = self.get_state()
        with open(filepath, 'wb') as f:
            pickle.dump(state, f)
    
    def load_state(self, filepath: str) -> Dict[str, Any]:
        """
        从文件加载状态
        Args:
            filepath: 文件路径
        Returns:
            Dict[str, Any]: 加载的状态
        """
        import pickle
        with open(filepath, 'rb') as f:
            return pickle.load(f)



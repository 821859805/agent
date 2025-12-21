#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
Brain 模块 - 核心推理引擎

本模块定义了 Agent 系统的核心推理引擎，包括：
- Brain: 大脑类，管理 LLM、工具、状态和不同的心智
- Mind: 心智类，负责执行具体的推理步骤

Brain 是 Agent 系统的核心，负责：
1. 管理多个 LLM 提供者（主 LLM 和专用 LLM）
2. 管理工具集合
3. 管理不同的心智（left_mind、right_mind、hippocampus_mind）
4. 处理输入和输出
5. 支持流式输出
6. 集成记忆系统（mem0）
"""

import uuid
from datetime import datetime
from typing import Any, Dict, Union

from jinja2 import Template
from pydantic import BaseModel

from minion.main.local_python_env import LocalPythonEnv

# 延迟导入 mem0（重量级依赖，仅在需要时加载）
Memory = None
def _get_memory_class():
    """
    获取 Memory 类（延迟导入）
    
    由于 mem0 是重量级依赖，使用延迟导入策略，仅在需要时加载。
    
    Returns:
        Memory 类
    """
    global Memory
    if Memory is None:
        from mem0 import Memory as _Memory
        Memory = _Memory
    return Memory
from tenacity import retry, stop_after_attempt, retry_if_exception_type

from minion import config
from minion.actions.lmp_action_node import LmpActionNode
from minion.main.input import Input
from minion.main.python_env import PythonEnv
from minion.main.async_python_executor import AsyncPythonExecutor
from minion.utils.utils import process_image
from minion.main.worker import ModeratorMinion
from minion.providers import create_llm_provider
from minion.types.agent_response import AgentResponse
from minion.types.agent_state import AgentState


class Mind(BaseModel):
    """
    心智类 - 负责执行具体的推理步骤
    
    每个 Mind 代表一种不同的推理策略或思维方式，例如：
    - left_mind: 逻辑推理和分析思维
    - right_mind: 创造性和艺术性思维
    - hippocampus_mind: 记忆形成和检索
    
    Mind 通过 ModeratorMinion 来执行实际的推理任务，支持普通执行和流式输出。
    
    使用示例：
        mind = Mind(id="left_mind", description="逻辑推理", brain=brain)
        result = await mind.step(input_obj)
    """
    id: str = "UnnamedMind"
    """心智 ID，用于标识不同的心智"""
    
    description: str = ""
    """心智描述，说明该心智的能力和特点"""
    
    brain: Any = None
    """Brain 实例的引用，用于访问 Brain 的资源（LLM、工具等）"""

    def step(self, input, selected_llm=None):
        """
        执行推理步骤
        
        根据输入是否启用流式输出，选择普通执行或流式执行。
        
        Args:
            input: Input 对象，包含查询和配置信息
            selected_llm: 可选的 LLM 提供者实例，用于覆盖默认 LLM
            
        Returns:
            - 如果 input.stream=True: 返回异步生成器（流式输出）
            - 如果 input.stream=False: 返回协程（普通执行）
            
        示例：
            # 普通执行
            result = await mind.step(input_obj)
            
            # 流式执行
            async for chunk in mind.step(input_obj):
                print(chunk)
        """
        moderator = ModeratorMinion(input=input, brain=self.brain, selected_llm=selected_llm)
        
        # 检查是否需要流式输出
        if hasattr(input, 'stream') and input.stream:
            # 流式输出：返回异步生成器
            return self._stream_step_generator(moderator)
        else:
            # 普通执行：返回协程
            return self._normal_step(moderator)
    
    async def _normal_step(self, moderator):
        """
        普通执行步骤
        
        执行一次推理步骤，等待完成后返回结果。
        
        Args:
            moderator: ModeratorMinion 实例，负责实际的推理执行
            
        Returns:
            AgentResponse: Agent 响应结果
        """
        agent_response = await moderator.execute()
        return agent_response
    
    async def _stream_step_generator(self, moderator):
        """
        流式步骤生成器
        
        流式执行推理步骤，逐步返回中间结果。
        
        Args:
            moderator: ModeratorMinion 实例，负责实际的推理执行
            
        Yields:
            StreamChunk: 流式输出块，包含中间结果
            
        注意：
            - 如果 moderator 支持 execute_stream()，使用真正的流式输出
            - 否则回退到普通执行，将结果作为单个块返回
        """
        # 检查 moderator 是否支持流式输出
        if hasattr(moderator, 'execute_stream'):
            # 使用真正的流式输出
            async for chunk in moderator.execute_stream():
                yield chunk
        else:
            # 回退到普通执行
            agent_response = await moderator.execute()
            yield agent_response


class Brain:
    """
    大脑类 - Agent 系统的核心推理引擎
    
    Brain 是 Agent 系统的核心组件，负责：
    1. 管理多个 LLM 提供者（主 LLM 和专用 LLM）
    2. 管理工具集合
    3. 管理不同的心智（left_mind、right_mind、hippocampus_mind）
    4. 处理输入和输出
    5. 支持流式输出
    6. 集成记忆系统（mem0）
    7. 管理 Python 执行环境
    
    使用示例：
        brain = Brain(
            llm="gpt-4",
            tools=[my_tool1, my_tool2],
            memory_config={"provider": "mem0"}
        )
        result = await brain.step(state)
    """
    
    def __init__(
        self,
        id=None,
        memory=None,
        memory_config=None,
        llm=create_llm_provider(config.models.get("default")),
        llms={},
        python_env=None,
        stats_storer=None,
        tools=None,
        state = None
    ):
        """
        初始化 Brain 实例
        
        Args:
            id: Brain 的唯一标识符，如果为 None 则自动生成 UUID
            memory: Memory 实例（mem0），如果为 None 且提供了 memory_config，则从配置创建
            memory_config: 记忆系统配置字典，用于创建 Memory 实例
            llm: 主 LLM 提供者，可以是字符串（模型名称）或 BaseProvider 实例
            llms: 专用 LLM 字典，键为名称（如 "code"、"math"），值为模型名称、提供者实例或列表
            python_env: Python 执行环境，如果为 None 则使用 LocalPythonEnv
            stats_storer: 统计存储器，用于记录执行统计信息
            tools: 工具列表，Agent 可以使用的工具集合
            state: AgentState 实例，如果为 None 则创建新的 AgentState
        """
        self.id = id or uuid.uuid4()
        """Brain 的唯一标识符"""
        
        self.minds = {}
        """心智字典，键为心智 ID，值为 Mind 实例"""
        
        # 添加默认的心智
        self.add_mind(
            Mind(
                id="left_mind",
                description="""
I'm the left mind, adept at logical reasoning and analytical thinking. I excel in tasks involving mathematics, language, and detailed analysis. My capabilities include:

Solving complex mathematical problems
Understanding and processing language with precision
Performing logical reasoning and critical thinking
Analyzing and synthesizing information systematically
Engaging in tasks that require attention to detail and structure""",
            )
        )
        """左脑：擅长逻辑推理和分析思维，适合数学、语言和详细分析任务"""
        
        self.add_mind(
            Mind(
                id="right_mind",
                description="""
I'm the right mind, flourishing in creative and artistic tasks. I thrive in activities that involve imagination, intuition, and holistic thinking. My capabilities include:

Creating and appreciating art and music
Engaging in creative problem-solving and innovation
Understanding and interpreting emotions and expressions
Recognizing patterns and spatial relationships
Thinking in a non-linear and abstract manner""",
            )
        )
        """右脑：擅长创造性和艺术性任务，适合想象、直觉和整体思维"""

        self.add_mind(
            Mind(
                id="hippocampus_mind",
                description="""I'm the hippocampus mind, specializing in memory formation, organization, and retrieval. I play a crucial role in both the storage of new memories and the recall of past experiences. My capabilities include:

Forming and consolidating new memories
Organizing and structuring information for easy retrieval
Facilitating the recall of past experiences and learned information
Connecting new information with existing knowledge
Supporting navigation and spatial memory""",
            )
        )
        """海马体心智：专门负责记忆形成、组织和检索"""

        # self.add_mind(Mind(id="hypothalamus", description="..."))
        # 初始化记忆系统
        self.mem = memory
        if not memory:
            if memory_config:
                MemoryClass = _get_memory_class()
                self.mem = memory = MemoryClass.from_config(memory_config)
        """记忆系统实例（mem0），用于长期记忆存储和检索"""

        # 处理主 LLM
        if isinstance(llm, str):
            self.llm = create_llm_provider(config.models.get(llm))
        else:
            self.llm = llm
        """主 LLM 提供者，用于大多数推理任务"""

        # 处理专用 LLM 字典
        self.llms = {}
        for key, value in llms.items():
            if isinstance(value, str):
                # 单个模型名称
                self.llms[key] = create_llm_provider(config.models.get(value))
            elif isinstance(value, list):
                # 模型名称或提供者列表
                self.llms[key] = [
                    item if not isinstance(item, str) else create_llm_provider(config.models.get(item))
                    for item in value
                ]
            else:
                # 假设已经是提供者实例
                self.llms[key] = value
        """专用 LLM 字典，键为名称（如 "code"、"math"），值为 BaseProvider 实例或列表"""

        # 设置工具集
        self.tools = tools or []
        """工具列表，Agent 可以使用的工具集合"""
        
        # Agent 状态引用（在执行时由 agent 设置）
        self.state = None
        """AgentState 实例引用，用于访问 Agent 的状态"""
        
        # 默认使用 LocalPythonEnv，提供 Python 代码执行能力
        self.python_env = LocalPythonEnv(verbose=False)
        """Python 执行环境，用于执行 Python 代码"""

        self.stats_storer = stats_storer
        """统计存储器，用于记录执行统计信息"""
        
        # 如果 state 为 None，创建新的 AgentState（当 Brain 独立使用时）
        if state is None:
            from minion.types.history import History
            state = AgentState(history=History())  # 当 brain 独立使用时，创建 AgentState 绑定，就像一个简单的 agent
        self.state = state

    def add_tool(self, tool):
        """
        添加工具到 Brain
        
        Args:
            tool: 要添加的工具实例
            
        示例：
            brain.add_tool(my_tool)
        """
        self.tools.append(tool)

    def add_mind(self, mind):
        """
        添加心智到 Brain
        
        Args:
            mind: Mind 实例，要添加到 Brain 的心智
            
        注意：
            - 会将 mind.brain 设置为 self，建立双向引用
            - 如果已存在相同 ID 的心智，会被覆盖
        """
        self.minds[mind.id] = mind
        mind.brain = self

    def process_image_input(self, input):
        """
        处理图像输入，将图像转换为 base64 格式
        
        支持字符串路径或字符串列表，会将所有图像转换为 base64 编码。
        
        Args:
            input: Input 对象，包含 images 字段
            
        Returns:
            Any: 处理后的图像（base64 编码的字符串或列表）
            
        Raises:
            ValueError: 如果 images 不是字符串或列表类型
            
        示例：
            input.images = "path/to/image.jpg"
            processed = brain.process_image_input(input)
            # processed 现在是 base64 编码的字符串
        """
        if input.images:
            if isinstance(input.images, str):
                input.images = process_image(input.images)
            elif isinstance(input.images, list):
                input.images = [process_image(img) for img in input.images]
            else:
                raise ValueError("input.images should be either a string or a list of strings/images")
        return input.images

    async def step(self, state: Union[AgentState, Input, Dict[str, Any]] = None, **config_kwargs):
        """
        执行一步推理
        
        这是 Brain 的核心方法，负责执行一次推理步骤。支持多种输入格式：
        - AgentState: 强类型状态对象
        - Input: 输入对象
        - Dict: 字典格式（向后兼容）
        
        执行流程：
        1. 处理不同类型的 state 参数
        2. 提取或创建 Input 对象
        3. 处理图像输入
        4. 设置工具和系统提示
        5. 选择合适的心智
        6. 执行推理（支持流式输出）
        
        Args:
            state: 状态对象，可以是：
                - AgentState: 强类型状态对象
                - Input: 输入对象
                - Dict[str, Any]: 字典格式（向后兼容）
                - None: 使用 Brain 自身的 state 或创建新的 AgentState
            **config_kwargs: 配置参数，可包含：
                - query: 查询字符串
                - query_type: 查询类型
                - system_prompt: 系统提示词
                - messages: OpenAI 格式的消息列表
                - stream: 是否启用流式输出
                - llm: 可选的 LLM 提供者（覆盖默认 LLM）
                - tools: 工具列表（覆盖默认工具）
                
        Returns:
            - 如果 stream=True: 返回异步生成器（流式输出）
            - 如果 stream=False: 返回 AgentResponse（普通执行）
            
        Raises:
            ValueError: 如果 state 类型不支持，或缺少必要的输入信息
            
        示例：
            # 使用 AgentState
            result = await brain.step(agent_state)
            
            # 使用 Input
            input_obj = Input(query="计算 1+1")
            result = await brain.step(input_obj)
            
            # 使用字典（向后兼容）
            result = await brain.step({"input": input_obj})
            
            # 流式输出
            async for chunk in brain.step(input_obj, stream=True):
                print(chunk)
        """
        # 处理不同类型的 state 参数
        if state is None:
            # 如果 state 为 None，使用 Brain 自身的 state 或创建新的 AgentState
            if self.state is not None:
                state = self.state
            else:
                from minion.types.history import History
                state = AgentState(history=History())

        # 根据 state 类型提取参数
        if isinstance(state, Input):
            # 直接是 Input 对象
            input = state
            current_tools = config_kwargs.get("tools", [])
        elif isinstance(state, AgentState):
            # 强类型 AgentState
            input = state.input
            current_tools = config_kwargs.get("tools", [])
        elif isinstance(state, dict):
            # 字典格式（向后兼容）
            state = AgentState.model_validate(state)  # 将字典转换为 AgentState
            input = state.input
            current_tools = config_kwargs.get("tools", [])
        else:
            raise ValueError(f"Unsupported state type: {type(state)}")

        # 注意：不在这里设置 self.state，因为：
        # 1. Brain 构造时已经通过 Brain(state=agent.state) 建立了引用关系
        # 2. agent.state 的任何修改都会自动反映到 brain.state
        # 3. 重新赋值会破坏引用关系，导致状态不同步
        # 4. 如果 brain.step() 被直接调用（不通过 agent），self.state 可能为 None，这是正常的
        # 但是，如果 self.state 为 None，我们需要设置它，以便后续访问
        if self.state is None:
            self.state = state
        # 更新 state 的 input 字段
        state.input = input  # 现在将 input 设置回 state，确保 state.input 是这个

        # 从 config_kwargs 提取其他参数
        query = config_kwargs.get("query", "")
        query_type = config_kwargs.get("query_type", "")
        system_prompt = config_kwargs.get("system_prompt")
        messages = config_kwargs.get("messages")
        stream = config_kwargs.get("stream", False)
        selected_llm = config_kwargs.get("llm")  # 可选的 LLM BaseProvider 实例
        
        # 如果 selected_llm 是字符串，转换为 BaseProvider 实例
        if isinstance(selected_llm, str):
            selected_llm = create_llm_provider(config.models.get(selected_llm))
        
        # 验证必须有 input 或者有 query/messages
        if input is None:
            if not query and messages is None:
                raise ValueError("State must contain 'input' or query/messages must be provided in config_kwargs")
            
            # 准备 Input 创建参数
            input_kwargs = config_kwargs.copy()
            input_kwargs.pop('query', None)
            input_kwargs.pop('query_type', None) 
            input_kwargs.pop('system_prompt', None)
            input_kwargs.pop('tools', None)
            input_kwargs.pop('messages', None)
            input_kwargs.pop('stream', None)
            input_kwargs.pop('llm', None)
            
            # 如果有 messages，优先使用 messages 创建 Input
            if messages is not None:
                # 支持标准 OpenAI messages 格式
                input = Input(query=messages, query_type=query_type, query_time=datetime.utcnow(), **input_kwargs)
            else:
                # 否则使用 query 创建 Input
                input = Input(query=query, query_type=query_type, query_time=datetime.utcnow(), **input_kwargs)
        
        # 如果传入了 messages，从中提取 system_prompt（如果没有显式提供的话）
        if messages is not None and system_prompt is None:
            if isinstance(messages, list):
                for msg in messages:
                    if isinstance(msg, dict) and msg.get("role") == "system":
                        system_prompt = msg.get("content", "")
                        break  # 只取第一个 system 消息
            
        input.query_id = input.query_id or uuid.uuid4()
        input.images = self.process_image_input(input)  # 将图像格式标准化为 base64
        
        # 设置工具和系统提示
        input.tools = current_tools
        if system_prompt is not None:
            input.system_prompt = system_prompt
        # 只有当 config_kwargs 中明确传递了 stream 参数时才覆盖
        if "stream" in config_kwargs:
            input.stream = stream

        # 选择心智（根据输入或自动选择）
        mind_id = input.mind_id or await self.choose_mind(input)
        # 根据心智类型调整 LLM 温度
        if mind_id == "left_mind":
            self.llm.config.temperature = 0.1  # 左脑：低温度，更确定性的输出
        elif mind_id == "right_mind":
            self.llm.config.temperature = 0.7  # 右脑：较高温度，更创造性的输出
        mind = self.minds[mind_id]
        
        # 检查是否需要流式输出
        if hasattr(input, 'stream') and input.stream:
            # 设置 stream_outputs 属性，供 UI 使用
            self.stream_outputs = True
            # 流式输出：直接返回异步生成器
            return mind.step(input, selected_llm=selected_llm)
        else:
            # 清除 stream_outputs 属性
            self.stream_outputs = False
            # 普通执行：await 结果并确保返回 AgentResponse
            result = await mind.step(input, selected_llm=selected_llm)
            
            # 确保结果是 AgentResponse 格式
            if not isinstance(result, AgentResponse):
                result = AgentResponse.from_tuple(result)
                
            return result

    def cleanup_python_env(self, input):
        """
        清理 Python 执行环境
        
        重置 Python 执行环境的状态，清除变量但保留工具。
        工具应该在多次执行之间保持持久化。
        
        Args:
            input: Input 对象（当前未使用，保留用于未来扩展）
            
        注意：
            - 对于支持 step() 的执行器，使用特殊关键字重置容器
            - 对于 LocalPythonExecutor 或 AsyncPythonExecutor，通过重新初始化状态来重置
            - 不清除工具，工具应该在多次执行之间保持持久化
            - 工具只在开始新会话时设置
        """
        if hasattr(self.python_env, 'step'):
            self.python_env.step("RESET_CONTAINER_SPECIAL_KEYWORD")
        else:
            # LocalPythonExecutor 或 AsyncPythonExecutor - 仅通过重新初始化状态来重置
            if hasattr(self.python_env, 'send_variables'):
                self.python_env.send_variables(variables={})
            # 不清除工具 - 工具应该在多次执行之间保持持久化
            # 工具只在开始新会话时设置

    async def choose_mind(self, input):
        """
        选择合适的心智来处理查询
        
        根据输入的查询类型和内容，自动选择最合适的心智。
        当前实现直接返回 "left_mind"，未来可能会使用 LLM 来选择。
        
        Args:
            input: Input 对象，包含查询信息
            
        Returns:
            str: 选择的心智 ID（如 "left_mind"、"right_mind"、"hippocampus_mind"）
            
        注意：
            - 当前实现直接返回 "left_mind"，不进行实际选择
            - 未来可能会使用 LLM 根据查询内容自动选择最合适的心智
            - 如果选择失败，默认返回 "left_mind"
        """
        return "left_mind"  # 目前不选择心智，直接返回 left_mind
        
        # 以下代码是未来可能使用的自动选择逻辑（当前已注释）
        mind_template = Template(
            """
I have minds:
{% for mind in minds %}
1. **ID:** {{ mind.id }}  
   **Description:** 
   "{{ mind.description }}"
{% endfor %}
According to the current user's query, 
which is of query type: {{ input.query_type }},
and user's query: {{ input.query }}
help me choose the right mind to process the query.
return the id of the mind, please note you *MUST* return exactly case same as I provided here, do not uppercase or downcase yourself.
"""
        )

        # 创建填充后的模板
        filled_template = mind_template.render(minds=self.minds.values(), input=input)

        try:
            lmp_action_node = LmpActionNode(llm=self.llm)
            result = await lmp_action_node.execute_answer(filled_template)

            # 确保结果是有效的心智 ID
            if result not in self.minds:
                result = "left_mind"
                # raise ValueError(f"Invalid mind ID returned: {result}")

            return result
        except Exception as e:
            return "left_mind"  # 对于 llama3.2 等无法返回有效 JSON 的模型，使用 EXISTING_ANSWER_PROMPT

    def run(self, query: str, **kwargs) -> AgentResponse:
        """
        同步接口：运行 Brain
        
        这是 run_async() 的同步包装器，使用 asyncio.run() 执行异步方法。
        适用于同步代码环境，但不推荐在异步环境中使用。
        
        Args:
            query: 要处理的查询字符串
            **kwargs: 附加参数，会传递给 run_async()
            
        Returns:
            AgentResponse: Brain 的响应结果
            
        示例：
            result = brain.run("计算 1+1")
        """
        import asyncio
        return asyncio.run(self.run_async(query, **kwargs))
    
    async def run_async(self, query: str, **kwargs):
        """
        异步接口：运行 Brain
        
        便捷方法，用于处理查询字符串。会自动创建 Input 对象并调用 step()。
        
        Args:
            query: 要处理的查询字符串
            **kwargs: 附加参数，可包含：
                - stream: 是否启用流式输出（默认 False）
                - 其他参数会设置到 Input 对象上（如果 Input 有对应属性）
                
        Returns:
            - 如果 stream=False: 返回 AgentResponse
            - 如果 stream=True: 返回异步生成器（流式输出）
            
        示例：
            # 普通执行
            result = await brain.run_async("计算 1+1")
            
            # 流式执行
            async for chunk in brain.run_async("计算 1+1", stream=True):
                print(chunk)
        """
        # 创建 Input 对象
        input_obj = Input(query=query)
        
        # 设置 stream 参数
        stream = kwargs.pop('stream', False)
        input_obj.stream = stream
        
        # 传递其他参数到 Input 对象
        for key, value in kwargs.items():
            if hasattr(input_obj, key):
                setattr(input_obj, key, value)
        
        # 使用 Input 调用 step 方法
        state = {"input": input_obj}
        return await self.step(state, **kwargs)
    
    async def run_stream(self, query: str, **kwargs):
        """
        流式接口：运行 Brain
        
        便捷方法，强制启用流式输出。等价于 run_async(query, stream=True, **kwargs)。
        
        Args:
            query: 要处理的查询字符串
            **kwargs: 附加参数，会传递给 run_async()
            
        Returns:
            AsyncGenerator: 响应流，逐步返回结果
            
        示例：
            async for chunk in brain.run_stream("计算 1+1"):
                print(chunk.content)
        """
        # 强制设置 stream=True
        kwargs['stream'] = True
        return await self.run_async(query, **kwargs)


Mind.model_rebuild()
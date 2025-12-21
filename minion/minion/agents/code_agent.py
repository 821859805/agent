#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CodeAgent - 基于代码思考的 Agent

CodeAgent 是一个"用代码思考"的 Agent，使用 Python 代码进行推理和行动。
它扩展了 BaseAgent，提供以下特性：

核心特性：
1. 基于代码的推理：使用 Python 代码而不是 JSON 进行推理
2. 自我反思能力：使用 "think" 工具进行自我反思
3. ReAct 循环：Reason-Act-Observe（推理-行动-观察）循环
4. 安全的代码执行：使用沙箱环境执行代码
5. 记忆集成：支持长期记忆存储和学习

主要组件：
- ThinkingEngine: 管理不同的思考策略和反思触发条件
- CodeAgentState: 扩展的状态管理，包含代码执行结果、错误计数等
- PythonExecutor: Python 代码执行器（支持同步和异步）

使用示例：
    agent = CodeAgent(
        llm="gpt-4",
        enable_reflection=True,
        use_async_executor=True
    )
    await agent.setup()
    result = await agent.solve_problem("计算斐波那契数列的前10项")
"""
from copy import copy
from typing import Dict, Any, List, Optional, Tuple, Union
from dataclasses import dataclass, field
import re
import traceback
import logging
import uuid
from datetime import datetime

from .base_agent import BaseAgent
from minion.types.agent_response import AgentResponse
from minion.types.agent_state import AgentState, CodeAgentState
from ..tools.base_tool import BaseTool
from ..main.input import Input
from ..main.local_python_executor import LocalPythonExecutor
from ..main.async_python_executor import AsyncPythonExecutor
from ..tools.default_tools import FinalAnswerTool

logger = logging.getLogger(__name__)

class ThinkingEngine:
    """
    思考引擎：管理不同的思考策略和反思触发条件
    
    这个类负责决定何时触发 Agent 的自我反思，基于以下条件：
    - 错误计数：达到一定错误次数后触发反思
    - 步数计数：每执行一定步数后触发反思
    - 低置信度：当置信度低于阈值时触发反思
    
    反思可以帮助 Agent 重新评估当前策略，发现错误模式，并调整方法。
    """
    
    def __init__(self, agent: 'CodeAgent'):
        """
        初始化思考引擎
        
        Args:
            agent: CodeAgent 实例的引用
        """
        self.agent = agent
        self.reflection_triggers = {
            'error_count': 3,  # 错误计数触发阈值：3 次错误后触发反思
            'step_count': 5,   # 步数计数触发阈值：每 5 步触发一次反思
            'low_confidence': 0.3,  # 低置信度触发阈值：置信度 < 0.3 时触发反思
        }
    
    def should_reflect(self, state: CodeAgentState) -> bool:
        """
        判断是否应该进行反思
        
        根据当前状态检查是否满足任何反思触发条件。
        
        Args:
            state: CodeAgentState 实例，包含错误计数、步数、置信度等信息
            
        Returns:
            bool: 如果需要反思返回 True，否则返回 False
        """
        # Check triggers
        if state.error_count >= self.reflection_triggers['error_count']:
            return True
        if state.step_count > 0 and state.step_count % self.reflection_triggers['step_count'] == 0:
            return True
        if state.last_confidence < self.reflection_triggers['low_confidence']:
            return True
        
        return False
    
    async def generate_reflection(self, state: CodeAgentState) -> str:
        """
        生成反思提示词
        
        基于当前状态生成一个反思提示，帮助 Agent 重新评估当前策略。
        提示词包含：
        - 任务描述
        - 当前进度
        - 错误统计
        - 最近行动
        - 反思问题
        
        Args:
            state: CodeAgentState 实例，包含任务、历史、错误计数等信息
            
        Returns:
            str: 格式化的反思提示词
        """
        history = state.history
        task = state.task or ''
        error_count = state.error_count
        
        reflection_prompt = f"""
Let me think about the current situation:

**Task**: {task}

**Progress so far**: {len(history)} steps completed
**Errors encountered**: {error_count}

**Recent actions**:
{self._format_recent_history(history[-3:] if history else [])}

**Reflection questions**:
1. Am I making progress toward the goal?
2. Are there any patterns in my errors?
3. Should I try a different approach?
4. What have I learned so far?
5. What should I do next?

Let me analyze this step by step using code...
"""
        return reflection_prompt
    
    def _format_recent_history(self, history: List[Any]) -> str:
        """
        格式化最近的历史记录用于反思
        
        提取最近几条历史记录并格式化为可读的文本。
        
        Args:
            history: 历史记录列表
            
        Returns:
            str: 格式化后的历史记录文本
        """
        if not history:
            return "No recent actions"
        
        formatted = []
        for i, step in enumerate(history[-3:], 1):
            if isinstance(step, tuple) and len(step) > 0:
                action = step[0]
                formatted.append(f"{i}. {action}")
        
        return '\n'.join(formatted) if formatted else "No recent actions"


@dataclass
class CodeAgent(BaseAgent):
    """
    基于代码思考的 Agent
    
    这是一个"用代码思考"的 Agent，使用 Python 代码进行推理和行动。
    它扩展了 BaseAgent，提供以下特性：
    
    核心特性：
    - 基于代码的推理：使用 Python 代码而不是 JSON 进行推理
    - 自我反思能力：使用 ThinkingEngine 进行自我反思
    - ReAct 循环：Reason-Act-Observe（推理-行动-观察）循环
    - 安全的代码执行：使用沙箱环境执行代码
    - 异步工具支持：支持异步工具调用
    - 可选的状态管理：支持持久化状态和对话历史跟踪
    
    属性说明：
        name: Agent 名称，默认为 "code_agent"
        thinking_engine: 思考引擎实例，管理反思策略
        python_executor: Python 代码执行器，可以是同步或异步
        enable_reflection: 是否启用自我反思功能
        max_code_length: 单个代码块的最大长度（字符数）
        use_async_executor: 是否使用异步执行器
        enable_state_tracking: 是否启用持久化状态跟踪功能
        conversation_history: 对话历史记录列表
        persistent_state: 持久化状态字典
        auto_save_state: 是否自动保存状态
        conversation_context_limit: 对话上下文限制（用于上下文窗口管理）
        state: CodeAgentState 实例，包含代码执行结果、错误计数等
    """
    
    name: str = "code_agent"
    """Agent 名称"""
    
    thinking_engine: Optional[ThinkingEngine] = None
    """思考引擎实例，管理反思策略和触发条件"""
    
    python_executor: Optional[Union[LocalPythonExecutor, AsyncPythonExecutor]] = None
    """Python 代码执行器，可以是同步（LocalPythonExecutor）或异步（AsyncPythonExecutor）"""
    
    enable_reflection: bool = True
    """是否启用自我反思功能"""
    
    max_code_length: int = 2000
    """单个代码块的最大长度（字符数），超过此长度的代码块不会被执行"""
    
    use_async_executor: bool = True
    """是否使用异步执行器，True 使用 AsyncPythonExecutor，False 使用 LocalPythonExecutor"""
    
    # ==================== 状态跟踪和对话历史（可选）====================
    enable_state_tracking: bool = False
    """是否启用持久化状态跟踪功能"""
    
    conversation_history: List[Dict[str, Any]] = field(default_factory=list)
    """对话历史记录列表，存储用户和助手的交互"""
    
    persistent_state: Dict[str, Any] = field(default_factory=dict)
    """持久化状态字典，存储跨会话的状态信息"""
    
    auto_save_state: bool = True
    """是否自动保存状态到持久化存储"""
    
    conversation_context_limit: int = 10
    """对话上下文限制，用于控制上下文窗口中的历史记录数量"""
    
    # ==================== 内部状态管理 ====================
    state: CodeAgentState = field(default_factory=CodeAgentState, init=False)
    """CodeAgentState 实例，包含代码执行结果、错误计数、反思计数等扩展状态"""
    
    def __post_init__(self):
        """
        初始化 CodeAgent，设置思考能力和可选的状态跟踪
        
        执行以下操作：
        1. 调用父类的 __post_init__
        2. 设置 state 中的 agent 引用
        3. 初始化思考引擎
        4. 初始化 Python 代码执行器（根据 use_async_executor 标志）
        """
        super().__post_init__()
        
        # Set agent reference in state if not already set
        if self.state and not self.state.agent:
            self.state.agent = self
        
        # Initialize thinking engine
        self.thinking_engine = ThinkingEngine(self)
        
        # Initialize code executor based on use_async_executor flag (brain is now available)
        if self.use_async_executor:
            self.python_executor = AsyncPythonExecutor(
                additional_authorized_imports=["numpy", "pandas", "matplotlib", "seaborn", "requests", "json", "csv", "asyncio","os","sys","*"],
                max_print_outputs_length=50000,
                additional_functions={}
            )
        else:
            self.python_executor = LocalPythonExecutor(
                additional_authorized_imports=["numpy", "pandas", "matplotlib", "seaborn", "requests", "json", "csv","os","sys","*"],
                max_print_outputs_length=50000,
                additional_functions={}
            )
        
    @property
    def history(self) -> List[Any]:
        """Get the history from internal state."""
        return self.state.history
    
    @history.setter
    def history(self, value: List[Any]):
        """Set the history in internal state."""
        self.state.history = value

    def _initialize_state(self):
        """
        如果启用状态跟踪，初始化持久化状态
        
        创建持久化状态字典，包含：
        - initialized_at: 初始化时间戳（UUID）
        - conversation_count: 对话计数
        - variables: 变量存储
        - memory_store: 内存存储
        - learned_patterns: 学习到的模式列表
        """
        if not self.persistent_state:
            self.persistent_state = {
                'initialized_at': str(uuid.uuid4()),
                'conversation_count': 0,
                'variables': {},
                'memory_store': {},
                'learned_patterns': []
            }
        logger.info("State tracking initialized")

    async def setup(self):
        """
        初始化 CodeAgent，设置工具和执行器
        
        执行以下操作：
        1. 调用父类的 setup() 初始化基础功能
        2. 将 Python 执行器设置到 brain.python_env
        3. 添加 FinalAnswerTool 工具
        4. 更新执行器的工具列表
        5. 如果存在 SkillTool，追加技能提示词
        6. 如果启用状态跟踪，初始化持久化状态
        
        注意：
        - 会临时重置 _is_setup 标志，因为父类会设置它为 True
        - 所有设置完成后才真正标记为已初始化
        """
        if self._is_setup:
            return
        await super().setup()
        self._is_setup = False  # 因为父类会设置它为 True，我们立即重置为 False

        # 步骤 1: 将 Python 执行器设置到 brain.python_env（brain 已初始化）
        if self.brain and self.python_executor:
            self.brain.python_env = self.python_executor

        # 步骤 2: 添加 FinalAnswerTool，用于标记最终答案
        self.add_tool(FinalAnswerTool())

        # 步骤 3: 将当前工具发送给 Python 执行器，使其可以在代码中调用
        self._update_executor_tools()

        # 步骤 4: 如果存在 SkillTool，追加技能提示词到系统提示
        self._append_skills_prompt()

        # 步骤 5: 如果启用状态跟踪，初始化持久化状态
        if self.enable_state_tracking:
            self._initialize_state()
        self._is_setup = True

    def _append_skills_prompt(self):
        """
        如果存在 SkillTool，将技能提示词追加到 system_prompt
        
        检查工具列表中是否有 SkillTool，如果有则：
        1. 生成技能提示词（包含可用技能列表）
        2. 将提示词追加到 system_prompt
        
        这样 Agent 就可以知道有哪些可用的技能可以使用。
        """
        from minion.tools.skill_tool import SkillTool, generate_skill_tool_prompt

        # Check if SkillTool is in tools
        has_skill_tool = any(isinstance(t, SkillTool) for t in self.tools)
        if not has_skill_tool:
            return

        # Generate skills prompt
        skills_prompt = generate_skill_tool_prompt()
        if not skills_prompt or "<available_skills>" not in skills_prompt:
            return

        # Append to system_prompt
        if self.system_prompt:
            self.system_prompt += "\n\n# Skills\n" + skills_prompt
        else:
            self.system_prompt = "# Skills\n" + skills_prompt

        logger.debug("Skills prompt appended to system_prompt")


    async def execute_step(self, state: CodeAgentState, stream: bool = False, **kwargs) -> AgentResponse:
        """
        执行步骤，增强的基于代码的推理
        
        这个方法重写了父类的 execute_step，添加了以下功能：
        1. 基于代码的推理：确保输入使用 'code' route
        2. 自我反思触发：根据状态决定是否进行反思
        3. 增强的错误处理：更好的异常处理和错误恢复
        
        执行流程：
        1. 检查是否需要反思（如果启用）
        2. 增强输入，确保使用代码思考模式
        3. 调用 brain.step 执行推理
        4. 转换结果为 AgentResponse 格式
        
        Args:
            state: 强类型的 CodeAgentState，包含代码执行结果、错误计数等
            stream: 是否流式返回结果
            **kwargs: 其他参数，会传递给 brain.step
            
        Returns:
            AgentResponse: 结构化的响应对象，而不是 5-tuple
            
        Raises:
            ValueError: 如果 state 中没有 input 或 brain 未初始化
        """
        # Use the provided state
        self.state = state
        
        # Extract input_data from internal state
        input_data = self.state.input
        if not input_data:
            raise ValueError("No input found in state")
        
        # Check if we should reflect first
        if self.enable_reflection and self.thinking_engine and self.thinking_engine.should_reflect(self.state):
            await self._perform_reflection()
        
        # Enhance the input with code minion routing
        enhanced_input = self._enhance_input_for_code_thinking(input_data)
        self.state.input = enhanced_input
        
        # Execute the step
        try:
            if not self.brain:
                raise ValueError("Brain is not initialized")
            
            # Get tools list from agent
            tools = self.tools
            
            # 同步state到brain，这样minion可以访问agent的状态
            self.brain.state = self.state
            
            # Call brain.step with enhanced input directly
            result = await self.brain.step(self.state, tools=tools, stream=stream, system_prompt=self.system_prompt, **kwargs)
            
            # Convert result to AgentResponse
            agent_response = AgentResponse.from_tuple(result)
            
            # Check if this is already a processed result (from CodeMinion with final_answer detection)
            # If brain already handled code execution and final_answer detection, don't re-process
            if hasattr(result, '__len__') and len(result) >= 5:
                response, score, terminated, truncated, info = result
                
                # Check if final_answer was already detected by the underlying system
                if isinstance(info, dict) and (
                    info.get('is_final_answer', False) or 
                    'final_answer' in info or
                    terminated
                ):
                    # Already processed by CodeMinion, use as-is
                    return agent_response
            
            return agent_response
            
        except Exception as e:
            logger.error(f"Step execution failed: {e}")
            raise e
    
    def _enhance_input_for_code_thinking(self, input_data: Input) -> Input:
        """
        增强输入，确保使用代码思考模式
        
        确保 Input 对象的 route 设置为 'code'，这样 Brain 会使用代码思考模式。
        
        Args:
            input_data: 输入数据对象
            
        Returns:
            Input: 增强后的输入对象（route 设置为 'code'）
        """
        # 确保 route 设置为 'code' 以使用代码思考模式
        enhanced_input = input_data
        if not enhanced_input.route:
            enhanced_input.route = 'code'
        
        return enhanced_input
    
    # step方法现在由BaseAgent处理，无需覆盖
    # BaseAgent.step已经返回AgentResponse，并且支持tuple解包向后兼容性
    
    async def _process_code_response(self, response: str) -> str:
        """
        处理并执行响应中找到的任何代码，支持 Thought-Code-Observation 循环
        
        这个方法实现了 ReAct（Reason-Act-Observe）循环中的"观察"部分：
        1. 从响应中提取 Python 代码块
        2. 执行每个代码块
        3. 收集执行结果和输出
        4. 构建观察反馈
        5. 检查是否找到最终答案
        
        如果代码执行成功，会将结果添加到状态中；如果失败，会记录错误并增加错误计数。
        
        Args:
            response: 包含代码块的响应文本
            
        Returns:
            str: 处理后的响应，包含原始响应和执行观察结果
            
        注意：
        - 超过 max_code_length 的代码块不会被执行
        - 如果检测到最终答案，会立即返回
        - 执行错误会记录到状态中，但不中断流程
        """
        
        # Extract Python code blocks from the response
        code_blocks = self._extract_code_blocks(response)
        
        if not code_blocks:
            # No code blocks found, return original response
            return response
        
        processed_parts = []
        processed_parts.append(response)
        
        # Process all code blocks, but check for final answer after each
        for i, code in enumerate(code_blocks):
            if len(code) > self.max_code_length:
                observation = f"\n**Observation:** Code block {i+1} too long to execute safely (max {self.max_code_length} characters)."
                processed_parts.append(observation)
                continue
            
            if not self.python_executor:
                observation = f"\n**Observation:** Python executor not available for code block {i+1}."
                processed_parts.append(observation)
                continue
                
            try:
                # Use AsyncPythonExecutor or LocalPythonExecutor to execute code
                if self.use_async_executor:
                    output, logs, is_final_answer = await self.python_executor(code)
                else:
                    output, logs, is_final_answer = self.python_executor(code)
                
                # Build observation feedback
                observation_parts = [f"\n**Observation:** Code block {i+1} executed successfully."]
                
                if logs:
                    # Clean and format log output
                    cleaned_logs = logs.strip()
                    if cleaned_logs:
                        observation_parts.append(f"```\n{cleaned_logs}\n```")
                
                if output is not None and not is_final_answer:
                    observation_parts.append(f"Return value: {output}")
                
                processed_parts.extend(observation_parts)
                
                # Store result in internal state for future reference
                self.state.add_code_result(i, output, logs, is_final_answer)
                
                # If this is the final answer, set global flag and return immediately
                if is_final_answer:
                    self.state.is_final_answer = True
                    self.state.final_answer_value = output
                    final_observation = f"\n**Final Answer Found:** {output}"
                    final_observation += f"\n**Task Status:** COMPLETED"
                    processed_parts.append(final_observation)
                    return '\n'.join(processed_parts)
                    
            except Exception as e:
                # Provide detailed error observation
                error_observation = f"\n**Observation:** Code block {i+1} execution failed."
                error_observation += f"\n**Error:** {str(e)}"
                
                # If there is traceback information, provide simplified version
                if hasattr(e, '__traceback__'):
                    try:
                        tb_lines = traceback.format_exception(type(e), e, e.__traceback__)
                        # Only take the last few lines of key information
                        key_lines = [line.strip() for line in tb_lines[-3:] if line.strip()]
                        if key_lines:
                            error_observation += f"\n**Traceback:** {' | '.join(key_lines)}"
                    except:
                        pass
                
                processed_parts.append(error_observation)
                
                # Increment error count
                self.state.error_count += 1
                
                # Provide error recovery suggestions
                if self.state.error_count <= 2:
                    recovery_suggestion = f"\n**Suggestion:** Review the error and try a different approach in the next step."
                    processed_parts.append(recovery_suggestion)
        
        return '\n'.join(processed_parts)
    
    def _contains_code_blocks(self, text: str) -> bool:
        """
        检查文本是否包含需要处理的代码块
        
        通过检查是否同时包含 '<end_code>' 和 '```' 来判断。
        
        Args:
            text: 要检查的文本
            
        Returns:
            bool: 如果包含代码块返回 True，否则返回 False
        """
        if not isinstance(text, str):
            return False
        return '<end_code>' in text and '```' in text
    
    def _extract_code_blocks(self, text: str) -> List[str]:
        """
        从文本中提取 Python 代码块，支持标准和 <end_code> 格式
        
        支持两种代码块格式：
        1. 标准格式：```python\n...\n```<end_code>
        2. 宽松格式：```python\n...<end_code>（没有闭合的 ```）
        
        提取的代码块会去重（保留顺序）。
        
        Args:
            text: 包含代码块的文本
            
        Returns:
            List[str]: 提取的代码块列表（已去重）
            
        注意：
        - 只处理字符串类型，非字符串会返回空列表
        - 使用正则表达式匹配，支持多行代码块
        """
        # Type safety check - only process strings
        if not isinstance(text, str):
            logger.warning(f"_extract_code_blocks received non-string input: {type(text)}")
            return []
        
        code_blocks = []
        
        # Pattern 2: Code blocks ending with <end_code>
        end_code_pattern = r'```(?:python|py)?\s*\n(.*?)\n```<end_code>'
        matches = re.findall(end_code_pattern, text, re.DOTALL)
        for match in matches:
            cleaned = match.strip()
            if cleaned:
                code_blocks.append(cleaned)
        
        # Pattern 3: Code blocks with just <end_code> at the end (no closing ```)
        loose_end_code_pattern = r'```(?:python|py)?\s*\n(.*?)<end_code>'
        matches = re.findall(loose_end_code_pattern, text, re.DOTALL)
        for match in matches:
            cleaned = match.strip()
            if cleaned:
                code_blocks.append(cleaned)
        
        # Remove duplicates while preserving order
        seen = set()
        unique_blocks = []
        for block in code_blocks:
            if block not in seen:
                seen.add(block)
                unique_blocks.append(block)
        
        return unique_blocks
    
    async def _perform_reflection(self) -> None:
        """
        执行自我反思，使用 think 工具
        
        当满足反思触发条件时，会调用此方法：
        1. 生成反思提示词
        2. 更新反思计数和最后反思步数
        3. 使用 think 工具执行反思
        4. 将反思添加到记忆中（如果可用）
        
        反思可以帮助 Agent 重新评估当前策略，发现错误模式，并调整方法。
        """
        if not self.thinking_engine:
            return
        
        # Use internal state for reflection
        reflection_prompt = await self.thinking_engine.generate_reflection(self.state)
        
        # Update reflection count
        self.state.reflection_count += 1
        self.state.last_reflection_step = self.state.step_count
        
        # Use the think tool
        think_tool = self.get_tool('think')
        if think_tool:
            think_tool.forward(reflection_prompt)
            
            # Add reflection to memory if available
            if hasattr(self, 'add_memory'):
                self.add_memory(
                    f"Reflection: {reflection_prompt}",
                    metadata={'type': 'reflection', 'timestamp': datetime.now().isoformat()}
                )
    
    async def update_state(self, state: CodeAgentState, result: Any) -> CodeAgentState:
        """Update state with CodeMinion-specific information."""
        # Update the internal state
        self.state = await super().update_state(state, result)

        # Extract confidence from result if available
        if isinstance(result, tuple) and len(result) >= 2:
            self.state.last_confidence = result[1]  # score/confidence

        return self.state
    
    async def solve_problem(self, problem: str, reset: bool = False, **kwargs) -> str:
        """
        使用基于代码的推理解决问题
        
        便捷方法，用于解决一般问题。会自动设置 route='code' 以使用代码思考模式。
        
        Args:
            problem: 要解决的问题描述
            reset: 如果为 True，在执行前重置 agent 状态（当启用状态跟踪时）
            **kwargs: 其他参数，会传递给 run_async
            
        Returns:
            str: 解决方案的字符串表示
            
        示例：
            result = await agent.solve_problem("计算 1 到 100 的和")
        """
        input_obj = Input(query=problem, route='code')
        result = await self.run_async(input_obj, reset=reset, **kwargs)
        return str(result)
    
    async def analyze_data(self, data: Any, question: str, reset: bool = False, **kwargs) -> str:
        """
        使用基于代码的推理分析数据
        
        便捷方法，用于分析数据并回答问题。会自动构建包含数据和问题的查询。
        
        Args:
            data: 要分析的数据（可以是任何类型）
            question: 关于数据的问题
            reset: 如果为 True，在执行前重置 agent 状态（当启用状态跟踪时）
            **kwargs: 其他参数，会传递给 run_async
            
        Returns:
            str: 分析结果的字符串表示
            
        示例：
            result = await agent.analyze_data([1, 2, 3, 4, 5], "计算平均值")
        """
        analysis_query = f"""
Analyze the following data and answer the question: {question}

Data: {data}

Use Python code to:
1. Understand the data structure
2. Perform necessary calculations
3. Generate insights
4. Answer the question
"""
        
        input_obj = Input(query=analysis_query, route='python')
        result = await self.run_async(input_obj, reset=reset, **kwargs)
        return str(result)
    
    def is_done(self, result: Any, state: CodeAgentState) -> bool:
        """
        检查任务是否完成，通过检测 is_final_answer 标志
        
        重写父类方法，添加了对 CodeAgentState.is_final_answer 的检查。
        检查顺序：
        1. 调用父类的 is_done 方法
        2. 检查 state.is_final_answer 标志
        3. 检查 result 的 is_done 方法（如果是 AgentResponse）
        4. 检查 result 的 terminated 标志（如果是 5-tuple）
        
        Args:
            result: 当前步骤结果，可以是 5-tuple 或 AgentResponse
            state: 当前状态（CodeAgentState）
            
        Returns:
            bool: 如果任务完成返回 True，否则返回 False
        """
        # Use the provided state
        self.state = state
        
        # First call the parent's is_done method
        parent_done = super().is_done(result, self.state)
        if parent_done:
            return True
        
        # Check if there is a final_answer flag in the internal state
        if self.state.is_final_answer:
            return True
        
        # For AgentResponse, use its built-in check method
        if hasattr(result, 'is_done'):
            return result.is_done()
        
        # Check if there is a termination flag in the result (5-tuple format)
        if isinstance(result, tuple) and len(result) >= 3:
            terminated = result[2]
            if terminated:
                return True
        
        return False
    
    def finalize(self, result: Any, state: CodeAgentState) -> Any:
        """
        整理最终结果，特别处理 final_answer 情况
        
        重写父类方法，优先从 CodeAgentState.final_answer_value 获取最终答案。
        优先级：
        1. state.final_answer_value（如果存在）
        2. result.final_answer（如果是 AgentResponse）
        3. 调用父类的 finalize 方法
        
        Args:
            result: 最后一步结果，可以是 5-tuple 或 AgentResponse
            state: 当前状态（CodeAgentState）
            
        Returns:
            Any: 最终处理后的结果
        """
        # Use the provided state
        self.state = state
        
        # Check if there is a final_answer_value in the internal state
        if self.state.final_answer_value is not None:
            return self.state.final_answer_value
        
        # For AgentResponse, prioritize using its final_answer
        if hasattr(result, 'final_answer') and result.final_answer is not None:
            return result.final_answer
        
        # Call parent's finalize method
        return super().finalize(result, self.state)

    def _update_executor_tools(self):
        """
        更新 Python 执行器的工具列表
        
        将当前 Agent 的工具发送给 Python 执行器，使其可以在代码中调用这些工具。
        工具会以两种名称注册：
        1. 原始名称：保持工具的原名
        2. Python 安全名称：将点号和连字符替换为下划线（如 "my.tool" -> "my_tool"）
        
        这样在 Python 代码中可以使用两种方式调用工具：
        - my.tool() 或 my_tool()
        """
        if self.python_executor and self.tools:
            if self.use_async_executor:
                # For AsyncPythonExecutor, pass tools directly - it will handle async/sync conversion
                tool_dict = {}
                for tool in self.tools:
                    if hasattr(tool, 'name'):
                        # Register with original name (for compatibility)
                        tool_dict[tool.name] = tool
                        # Also register with Python-safe name (dots/dashes replaced with underscores)
                        safe_name = tool.name.replace('.', '_').replace('-', '_')
                        if safe_name != tool.name:
                            tool_dict[safe_name] = tool
                self.python_executor.send_tools(tool_dict)
            else:
                # Same logic for sync executor
                tool_dict = {}
                for tool in self.tools:
                    if hasattr(tool, 'name'):
                        tool_dict[tool.name] = tool
                        safe_name = tool.name.replace('.', '_').replace('-', '_')
                        if safe_name != tool.name:
                            tool_dict[safe_name] = tool
                self.python_executor.send_tools(tool_dict)
    
    def add_tool(self, tool: BaseTool):
        """
        添加工具并更新执行器
        
        重写父类方法，在添加工具后自动更新 Python 执行器的工具列表。
        
        Args:
            tool: 要添加的工具实例
        """
        super().add_tool(tool)
        # Update executor tools whenever a new tool is added
        if hasattr(self, 'python_executor'):
            self._update_executor_tools()
            
    # State management methods from StateCodeAgent
    
    def run(self, 
           task: Optional[Union[str, Input]] = None,
           max_steps: Optional[int] = None,
           reset: bool = False,
           route: Optional[str] = None,
           **kwargs) -> Any:
        """
        Synchronous interface for running the agent using internal state.
        
        Args:
            task: Task description or Input object
            max_steps: Maximum number of steps
            reset: If True, reset the agent state before execution
            route: 可选的route名称，如 "code", "cot", "plan" 等，指定使用哪个minion
            **kwargs: Additional parameters
            
        Returns:
            Final task result
        """
        import asyncio
        return asyncio.run(self.run_async(task=task, max_steps=max_steps, reset=reset, stream=False, route=route, **kwargs))

    async def run_async(self, task: Optional[Union[str, Input]] = None,
                       max_steps: Optional[int] = None,
                       reset: bool = False,
                       stream: bool = False,
                       route: Optional[str] = None,
                       **kwargs) -> Any:
        """
        Run the CodeAgent with code-thinking capabilities using internal state.
        
        Args:
            task: Task description or Input object
            max_steps: Maximum steps to execute
            reset: If True, reset the agent state before execution
            stream: If True, return streaming generator
            route: 可选的route名称，如 "code", "cot", "plan" 等，指定使用哪个minion
            **kwargs: Additional parameters
            
        Returns:
            Agent response or async generator for streaming
        """
        # Prepare input and internal state
        enhanced_input = self._prepare_input(task, route=route)
        self._prepare_internal_state(task, reset)
        
        # Record input in state for interaction tracking
        self.state.input = enhanced_input
        
        try:
            # Use BaseAgent's logic but with our enhanced input and internal state
            result = await super().run_async(
                task=enhanced_input,
                state=self.state, 
                max_steps=max_steps, 
                stream=stream, 
                route=route,
                **kwargs
            )
            
            # Record interaction if state tracking is enabled
            if self.enable_state_tracking:
                await self._record_interaction(enhanced_input, result, reset)
                if self.auto_save_state:
                    self._save_persistent_state(self.state)
            
            return result
            
        except Exception as e:
            # Record failed interaction if state tracking is enabled
            if self.enable_state_tracking:
                await self._record_interaction(enhanced_input, f"Error: {e}", reset)
            raise
    
    def _prepare_input(self, task: Optional[Union[str, Input]], route: Optional[str] = None) -> Input:
        """
        准备输入数据用于执行
        
        将任务描述或 Input 对象转换为增强的 Input 对象。
        路由优先级：
        1. 显式提供的 route 参数
        2. Input 对象中已有的 route
        3. 默认 'code' route
        
        Args:
            task: 任务描述（字符串）或 Input 对象
            route: 可选的 route 名称，如果提供则覆盖默认的 'code' route
            
        Returns:
            Input: 准备好的 Input 对象，包含增强的查询
            
        Raises:
            ValueError: 如果 task 不是字符串或 Input 对象
        """
        # Convert string task to Input if needed
        if isinstance(task, str):
            # Use provided route or default to 'code'
            default_route = route if route is not None else 'code'
            input_data = Input(query=task, route=default_route)
        elif isinstance(task, Input):
            input_data = task
            # Set route based on priority: explicit route param > existing route > default 'code'
            if route is not None:
                input_data.route = route
            elif not input_data.route:
                input_data.route = 'code'
        else:
            raise ValueError(f"Task must be string or Input object, got {type(task)}")
        
        # Enhance input with code-thinking instructions, do not repeat what's done in CodeMinion
        enhanced_input = input_data

        return enhanced_input
    
    def _prepare_internal_state(self, task: Optional[Union[str, Input]], reset: bool) -> None:
        """
        准备内部状态用于执行
        
        初始化或重置 CodeAgentState，设置任务信息，并合并持久化状态（如果启用）。
        
        Args:
            task: 任务描述或 Input 对象
            reset: 是否在执行前重置状态
        """
        # Initialize internal state if needed
        if not hasattr(self, 'state') or self.state is None:
            self.state = CodeAgentState(agent=self)
        
        # Handle reset functionality
        if reset:
            if self.enable_state_tracking:
                # Reset both internal state and persistent state
                self.reset_state()
                logger.info("Agent state has been reset (including persistent state)")
            else:
                # Just reset internal state
                self.state.reset()
                logger.info("Agent internal state has been reset")
        
        # Set task information
        if task is not None:
            if isinstance(task, str):
                self.state.task = task
            else:
                self.state.task = task.query
        
        # Add persistent information if state tracking is enabled
        if self.enable_state_tracking:
            # Merge persistent state into metadata
            self.state.metadata.update(self.persistent_state)
            self.state.metadata['conversation_history'] = self.get_recent_history()
    
    def reset_state(self) -> None:
        """
        重置 Agent 状态
        
        清除以下内容：
        - 对话历史
        - 工作变量
        - 临时内存
        - 内部状态
        
        但保留：
        - 学习到的模式（learned_patterns）
        - 核心配置
        
        如果启用状态跟踪，还会：
        - 重置会话 ID
        - 重置持久化状态（但保留学习到的模式）
        - 重置代码执行器状态（如果支持）
        """
        # Reset internal state first
        if hasattr(self, 'state') and self.state:
            self.state.reset()
        else:
            self.state = CodeAgentState(agent=self)
        
        if self.enable_state_tracking:
            # Clear conversation history
            self.conversation_history = []
            
            # Reset session ID
            self.session_id = str(uuid.uuid4())
            
            # Reset working state but preserve learned patterns
            learned_patterns = self.persistent_state.get('learned_patterns', [])
            self.persistent_state = {
                'initialized_at': str(uuid.uuid4()),
                'conversation_count': 0,
                'variables': {},
                'memory_store': {},
                'learned_patterns': learned_patterns  # Preserve learned patterns
            }
        
        # Reset code executor state if available
        if self.python_executor:
            if hasattr(self.python_executor, 'reset'):
                self.python_executor.reset()
        
        logger.info("Agent state reset completed")
    
    def get_state(self) -> Dict[str, Any]:
        """
        获取当前 Agent 状态，包括对话和持久化状态
        
        返回包含以下信息的字典：
        - conversation_history: 对话历史记录
        - persistent_state: 持久化状态
        - session_id: 会话 ID
        - conversation_count: 对话计数（近似值）
        
        Returns:
            Dict[str, Any]: 完整的状态字典，如果状态跟踪未启用则返回空字典
        """
        if not self.enable_state_tracking:
            return {}
            
        return {
            'conversation_history': self.conversation_history,
            'persistent_state': self.persistent_state,
            'session_id': self.session_id,
            'conversation_count': len(self.conversation_history) // 2,  # Approximate turns
        }
    
    def load_state(self, state: Dict[str, Any]) -> None:
        """
        从字典加载 Agent 状态
        
        恢复之前保存的状态，包括：
        - 对话历史
        - 持久化状态
        - 会话 ID
        
        Args:
            state: 要加载的状态字典
            
        注意：
        - 如果状态跟踪未启用，此方法无效
        - 加载的状态会覆盖当前状态
        """
        if not self.enable_state_tracking:
            logger.warning("State tracking is disabled, load_state has no effect")
            return
            
        if 'conversation_history' in state:
            self.conversation_history = state['conversation_history']
        
        if 'persistent_state' in state:
            self.persistent_state = state['persistent_state']
        
        if 'session_id' in state:
            self.session_id = state['session_id']
        
        logger.info(f"Agent state loaded with {len(self.conversation_history)} conversation entries")
    
    def _add_conversation_context(self, input_data: Input) -> Input:
        """
        为输入添加对话上下文，以保持更好的连续性
        
        从对话历史中提取最近的对话记录，并将其添加到输入中。
        这样 Agent 可以：
        - 考虑之前的对话内容
        - 保持一致性
        - 使用之前步骤的变量和结果
        
        Args:
            input_data: 输入数据对象
            
        Returns:
            Input: 增强后的输入对象，包含对话上下文
            
        注意：
        - 只在使用状态跟踪时有效
        - 上下文数量受 conversation_context_limit 限制
        """
        if not self.enable_state_tracking or not self.conversation_history:
            return input_data
        
        # Get recent conversation for context
        recent_history = self.get_recent_history(limit=self.conversation_context_limit)
        
        if not recent_history:
            return input_data
        
        # Format conversation context
        context_lines = []
        for entry in recent_history:
            role = entry['role'].upper()
            content = str(entry['content'])[:200]  # Limit content length
            context_lines.append(f"{role}: {content}")
        
        conversation_context = "\n".join(context_lines)
        
        # Enhanced query with conversation context
        enhanced_query = f"""**Conversation Context:**
{conversation_context}

**Current Request:**
{input_data.query}

**Instructions:**
- Consider the conversation context when responding
- Maintain consistency with previous interactions
- Use any relevant information from the conversation history
- If variables or results from previous steps are relevant, reference them in your code
"""
        
        return Input(
            query=enhanced_query,
            route=getattr(input_data, 'route', None) or 'code',
            check=getattr(input_data, 'check', False),
            dataset=getattr(input_data, 'dataset', None),
            metadata=getattr(input_data, 'metadata', {})
        )
    
    async def _record_interaction(self, input_data: Input, result: Any, was_reset: bool) -> None:
        """
        在对话历史中记录交互
        
        记录用户输入和系统响应到对话历史中，用于后续的上下文管理。
        
        Args:
            input_data: 用户输入数据
            result: 系统响应结果
            was_reset: 是否在执行前重置了状态
            
        注意：
        - 只在使用状态跟踪时有效
        - 如果状态被重置，会添加重置标记
        - 会自动更新持久化状态中的对话计数
        """
        if not self.enable_state_tracking:
            return
            
        # Record user input
        self.add_to_history("user", input_data.query)
        
        # Record system response
        if isinstance(result, AgentResponse):
            response_content = result.raw_response
        else:
            response_content = str(result)
        
        self.add_to_history("assistant", response_content)
        
        # Add reset indicator if state was reset
        if was_reset:
            self.add_to_history("system", "State was reset before this interaction")
        
        # Update conversation count in persistent state
        self.persistent_state['conversation_count'] = len(self.conversation_history) // 2
    
    def _save_persistent_state(self, current_state: Dict[str, Any]) -> None:
        """Save relevant information to persistent state."""
        if not self.enable_state_tracking:
            return
            
        # Extract variables from execution state
        variables = {}
        for key, value in current_state.items():
            if key.startswith('code_result_'):
                variables[key] = value
        
        if variables:
            self.persistent_state['variables'].update(variables)
        
        # Save any learned patterns or insights
        if 'learned_patterns' in current_state:
            self.persistent_state['learned_patterns'].extend(current_state['learned_patterns'])
    
    def get_recent_history(self, limit: Optional[int] = None) -> List[Dict[str, Any]]:
        """Get recent conversation history."""
        if not self.enable_state_tracking:
            return []
            
        if limit is None:
            return self.conversation_history
        return self.conversation_history[-limit:] if self.conversation_history else []
    
    def clear_history(self) -> None:
        """Clear conversation history while preserving persistent state."""
        if not self.enable_state_tracking:
            logger.warning("State tracking is disabled, clear_history has no effect")
            return
            
        self.conversation_history = []
        self.session_id = str(uuid.uuid4())
        logger.info("Conversation history cleared")
    
    def get_conversation_history(self) -> List[Dict[str, Any]]:
        """Get complete conversation history."""
        if not self.enable_state_tracking:
            return []
            
        return self.conversation_history
    
    def add_to_history(self, role: str, content: Any) -> None:
        """
        Add entry to conversation history.
        
        Args:
            role: Role (user, assistant, system)
            content: Content of the message
        """
        if not self.enable_state_tracking:
            return
            
        self.conversation_history.append({
            "role": role,
            "content": content,
            "timestamp": str(uuid.uuid4())[:8]  # Short timestamp
        })
    
    def get_statistics(self) -> Dict[str, Any]:
        """Get conversation and usage statistics."""
        if not self.enable_state_tracking:
            return {
                'state_tracking': 'disabled'
            }
            
        return {
            'total_conversations': self.persistent_state.get('conversation_count', 0),
            'current_session_messages': len(self.conversation_history),
            'session_id': self.session_id,
            'variables_stored': len(self.persistent_state.get('variables', {})),
            'patterns_learned': len(self.persistent_state.get('learned_patterns', [])),
            'auto_save_enabled': self.auto_save_state
        }
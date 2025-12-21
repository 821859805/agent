#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ToolCallingAgent - 工具调用 Agent

ToolCallingAgent 目前与 BaseAgent 功能相同，保留此类的目的是：
1. 向后兼容：保持 API 的一致性
2. 未来扩展：为将来可能的工具调用特定功能预留接口
3. 语义清晰：明确表示这是一个专门用于工具调用的 Agent

注意：
- 当前实现中，ToolCallingAgent 与 BaseAgent 完全相同
- 所有功能都继承自 BaseAgent
- 如果需要工具调用特定的功能，可以在此类中添加

使用示例：
    # 当前使用方式与 BaseAgent 相同
    agent = ToolCallingAgent(
        llm="gpt-4",
        tools=[my_tool1, my_tool2]
    )
    await agent.setup()
    result = await agent.run_async("使用工具完成任务")
"""

from typing import Dict, Any, List, Optional, Union, AsyncGenerator
import json
import logging
import asyncio
from concurrent.futures import ThreadPoolExecutor, as_completed
import inspect
from dataclasses import dataclass, field

from .base_agent import BaseAgent
from ..main.input import Input
from ..main.action_step import StreamChunk
from ..tools.base_tool import BaseTool
from ..schema.message_types import ToolCall
from ..providers import create_llm_provider
from .. import config
from ..exceptions import FinalAnswerException
from minion.types.agent_response import AgentResponse

logger = logging.getLogger(__name__)


@dataclass
class ToolCallingAgent(BaseAgent):
    """
    工具调用 Agent
    
    这是一个专门用于工具调用的 Agent 类。目前与 BaseAgent 功能完全相同，
    保留此类的目的是为了向后兼容和未来扩展。
    
    特性：
    - 继承 BaseAgent 的所有功能
    - 支持工具调用
    - 支持流式输出
    - 支持状态管理
    
    注意：
    - 当前实现中，ToolCallingAgent 与 BaseAgent 完全相同
    - 所有功能都继承自 BaseAgent
    - 如果需要工具调用特定的功能，可以在此类中添加
    
    未来可能的扩展：
    - 工具调用优化
    - 并行工具调用
    - 工具调用缓存
    - 工具调用验证
    """
    # ToolCallingAgent 目前与 BaseAgent 相同
    # 保留此类是为了向后兼容和未来扩展
    pass
#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
CodeAgent Example: Demonstrating "think in code" functionality

This example shows how to use the CodeAgent for various tasks:
- Mathematical problem solving
- Data analysis
- Complex reasoning with self-reflection

CodeAgent 示例：演示"代码思考"功能

本示例展示了如何使用 CodeAgent 完成各种任务：
- 数学问题求解
- 数据分析
- 带自我反思的复杂推理
"""

import asyncio
import os
import sys

# 将项目根目录添加到 Python 路径中，以便导入 minion 模块
# Add the project root to Python path
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

# 导入必要的模块
# Import necessary modules
from minion import config  # 配置模块，包含模型配置信息
from minion.agents import CodeAgent  # CodeAgent：能够通过编写和执行代码来解决问题的智能体
from minion.main.local_python_executor import LocalPythonExecutor  # 本地 Python 代码执行器
from minion.main.brain import Brain  # Brain：智能体的"大脑"，整合 LLM 和代码执行环境
from minion.providers import create_llm_provider  # 创建 LLM 提供者的工厂函数


async def basic_math_example():
    """
    示例 1：基础数学问题求解
    Example 1: Basic mathematical problem solving
    
    演示如何使用 CodeAgent 解决基础的数学问题
    """
    print("=== Basic Math Example ===")
    
    # 从配置中获取 LLM 模型配置（这里使用 gpt-4.1）
    # Setup LLM and LocalPythonExecutor
    llm_config = config.models.get("gpt-4.1")
    # 创建 LLM 提供者实例
    llm = create_llm_provider(llm_config)
    
    # 创建本地 Python 执行器，配置允许导入的模块
    # Use LocalPythonExecutor for Brain
    python_executor = LocalPythonExecutor(
        additional_authorized_imports=["numpy", "pandas", "matplotlib", "seaborn", "requests", "json", "csv"],  # 允许导入的额外模块
        max_print_outputs_length=50000,  # 最大打印输出长度限制
        additional_functions={}  # 额外的自定义函数（这里为空）
    )
    
    # 创建 Brain 实例，将 Python 执行器和 LLM 组合在一起
    # Create Brain with python_env
    brain = Brain(python_env=python_executor, llm=llm)
    
    # 创建 CodeAgent 实例，传入配置好的 Brain
    # Create CodeAgent
    code_agent = CodeAgent(brain=brain)
    
    # 定义一个数学问题：计算半径为 5 的圆的面积，然后找出使面积翻倍的半径
    # Solve a math problem
    problem = "Calculate the area of a circle with radius 5, and then find what radius would give double that area."
    
    try:
        # 使用 CodeAgent 解决问题，这是一个异步操作
        result = await code_agent.solve_problem(problem)
        print(f"Problem: {problem}")
        print(f"Result: {result}")
    except Exception as e:
        # 如果出现错误，打印错误信息和堆栈跟踪
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()
    print()


async def data_analysis_example():
    """
    示例 2：数据分析（带反思）
    Example 2: Data analysis with reflection
    
    演示如何使用 CodeAgent 进行数据分析，CodeAgent 会编写代码来分析数据并给出见解
    """
    print("=== Data Analysis Example ===")
    
    # 设置 LLM 和 Python 执行器
    # Setup
    llm_config = config.models.get("gpt-4.1")
    llm = create_llm_provider(llm_config)
    
    # 配置 Python 执行器，允许导入数据分析相关的库
    python_executor = LocalPythonExecutor(
        additional_authorized_imports=["numpy", "pandas", "matplotlib", "seaborn", "json"],  # 数据分析常用库
        max_print_outputs_length=50000
    )
    brain = Brain(python_env=python_executor, llm=llm)
    
    # 创建 CodeAgent
    # Create CodeAgent
    code_agent = CodeAgent(brain=brain)
    
    # 定义示例销售数据（包含月份、销售额和成本）
    # Sample data
    sales_data = [
        {"month": "Jan", "sales": 1000, "costs": 800},
        {"month": "Feb", "sales": 1200, "costs": 900},
        {"month": "Mar", "sales": 1500, "costs": 1100},
        {"month": "Apr", "sales": 1300, "costs": 1000},
        {"month": "May", "sales": 1800, "costs": 1200},
    ]
    
    # 构建数据分析问题，要求 CodeAgent 分析数据并回答多个问题
    # Analyze the data
    problem = f"""
    I have sales data: {sales_data}
    
    Analyze this data and answer:
    1. What are the trends in profit margin over these months? 
    2. Which month had the best performance?
    3. Calculate the total profit for all months.
    """
    
    try:
        # 执行数据分析
        result = await code_agent.solve_problem(problem)
        print(f"Analysis Problem: {problem}")
        print(f"Analysis Result: {result}")
    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()
    print()


async def complex_reasoning_example():
    """
    示例 3：多步骤复杂推理
    Example 3: Complex reasoning with multiple steps
    
    演示 CodeAgent 如何处理需要多个步骤和复杂推理的问题
    """
    print("=== Complex Reasoning Example ===")
    
    # 设置 LLM 和 Python 执行器
    # Setup
    llm_config = config.models.get("gpt-4.1")
    llm = create_llm_provider(llm_config)
    
    # 配置 Python 执行器，允许导入数学计算相关的库
    python_executor = LocalPythonExecutor(
        additional_authorized_imports=["numpy", "math"],  # 数学计算库
        max_print_outputs_length=50000
    )
    brain = Brain(python_env=python_executor, llm=llm)
    
    # 创建 CodeAgent
    # Create CodeAgent
    code_agent = CodeAgent(brain=brain)
    
    # 定义一个复杂的商业决策问题，需要多步计算和比较分析
    # Complex problem that requires multiple steps
    problem = """
    A company has the following situation:
    - They have 100 employees
    - Each employee works 40 hours per week
    - The company pays $25 per hour on average
    - They want to increase productivity by 20%
    - They're considering either hiring more people or increasing hours
    
    Calculate:
    1. Current weekly labor cost
    2. If they hire 20% more people, what's the new cost?
    3. If they increase hours by 20% instead, what's the new cost?
    4. Which option is more cost-effective?
    5. What would be the hourly productivity gain needed to justify the hiring option?
    """
    
    try:
        # 执行复杂推理和计算
        result = await code_agent.solve_problem(problem)
        print(f"Complex Problem: {problem}")
        print(f"Complex Analysis Result: {result}")
    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()
    print()


async def fibonacci_optimization_example():
    """
    示例 4：迭代式问题求解与优化
    Example 4: Iterative problem solving with optimization
    
    演示 CodeAgent 如何从基础实现开始，然后进行优化改进
    """
    print("=== Fibonacci Optimization Example ===")
    
    # 设置 LLM 和 Python 执行器
    # Setup
    llm_config = config.models.get("gpt-4.1")
    llm = create_llm_provider(llm_config)
    
    # 配置 Python 执行器，允许导入时间和函数工具相关的库
    python_executor = LocalPythonExecutor(
        additional_authorized_imports=["time", "functools"],  # time 用于性能测试，functools 用于优化（如缓存）
        max_print_outputs_length=50000
    )
    brain = Brain(python_env=python_executor, llm=llm)
    
    # 创建 CodeAgent
    # Create CodeAgent
    code_agent = CodeAgent(brain=brain)
    
    # 定义一个需要优化的算法问题：计算斐波那契数列
    # Problem that can be optimized
    problem = """
    Write a function to calculate the 50th Fibonacci number.
    Start with a basic recursive approach, then optimize it.
    Compare the performance of different approaches and show the results.
    """
    
    try:
        # CodeAgent 会先写基础递归版本，然后优化为更高效的版本，并比较性能
        result = await code_agent.solve_problem(problem)
        print(f"Fibonacci Problem: {problem}")
        print(f"Fibonacci Optimization Result: {result}")
    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()
    print()


async def circle_area_example():
    """
    示例 5：简单的圆面积计算（smolagents 风格的代码执行）
    Example 5: Simple circle area calculation with smolagents-style code execution
    
    这是一个简单的示例，用于测试 CodeAgent 的基本代码执行功能
    """
    print("=== Circle Area Example (smolagents style) ===")
    
    # 设置 LLM 和 Python 执行器
    # Setup
    llm_config = config.models.get("gpt-4.1")
    llm = create_llm_provider(llm_config)
    
    # 配置 Python 执行器，只允许导入 math 模块
    python_executor = LocalPythonExecutor(
        additional_authorized_imports=["math"],  # 只需要数学模块来计算圆面积
        max_print_outputs_length=50000
    )
    brain = Brain(python_env=python_executor, llm=llm)
    
    # 创建 CodeAgent
    # Create CodeAgent
    code_agent = CodeAgent(brain=brain)
    
    # 定义一个简单的数学问题，用于测试代码执行功能
    # Simple problem to test <end_code> functionality
    problem = "Calculate the area of a circle with radius 5. Use the math.pi constant for accuracy."
    
    try:
        # 执行简单的计算任务
        result = await code_agent.solve_problem(problem)
        print(f"Circle Problem: {problem}")
        print(f"Circle Area Result: {result}")
    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()
    print()

async def main():
    """
    主函数：运行所有示例
    Run all examples
    
    按顺序执行所有 CodeAgent 示例，展示不同的使用场景
    """
    print("CodeAgent Examples with LocalPythonExecutor")
    print("=" * 50)
    
    try:
        # 依次运行各个示例
        await basic_math_example()  # 基础数学问题
        await circle_area_example()  # 圆面积计算
        await data_analysis_example()  # 数据分析
        await complex_reasoning_example()  # 复杂推理
        await fibonacci_optimization_example()  # 算法优化
    except Exception as e:
        # 如果运行过程中出现错误，打印错误信息
        print(f"Error running examples: {e}")
        import traceback
        traceback.print_exc()


# 程序入口：当直接运行此脚本时执行 main 函数
if __name__ == "__main__":
    # 使用 asyncio.run() 运行异步主函数
    asyncio.run(main())
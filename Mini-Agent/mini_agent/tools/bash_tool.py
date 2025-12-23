"""Shell command execution tool with background process management.

Supports both bash (Unix/Linux/macOS) and PowerShell (Windows).
"""

import asyncio
import platform
import re
import time
import uuid
from typing import Any

from pydantic import Field, model_validator

from .base import Tool, ToolResult


class BashOutputResult(ToolResult):
    """Bash command execution result with separated stdout and stderr.

    Inherits from ToolResult which provides:
    - success: bool
    - content: str (used for formatted output message, auto-generated from stdout/stderr)
    - error: str | None (used for error messages)
    """

    stdout: str = Field(description="The command's standard output")
    stderr: str = Field(description="The command's standard error output")
    exit_code: int = Field(description="The command's exit code")
    bash_id: str | None = Field(default=None, description="Shell process ID (only when run_in_background=True)")

    @model_validator(mode="after")
    def format_content(self) -> "BashOutputResult":
        """Auto-format content from stdout and stderr if content is empty."""
        output = ""
        if self.stdout:
            output += self.stdout
        if self.stderr:
            output += f"\n[stderr]:\n{self.stderr}"
        if self.bash_id:
            output += f"\n[bash_id]:\n{self.bash_id}"
        if self.exit_code:
            output += f"\n[exit_code]:\n{self.exit_code}"

        if not output:
            output = "(no output)"

        self.content = output
        return self


class BackgroundShell:
    """后台shell数据容器.

    仅用于存储状态和输出的纯数据类。
    IO操作由外部的BackgroundShellManager类统一管理
    """

    def __init__(self, bash_id: str, command: str, process: "asyncio.subprocess.Process", start_time: float):
        self.bash_id = bash_id
        self.command = command
        self.process = process
        self.start_time = start_time
        self.output_lines: list[str] = []
        self.last_read_index = 0
        self.status = "running"
        self.exit_code: int | None = None

    def add_output(self, line: str):
        """添加新输出行"""
        self.output_lines.append(line)

    def get_new_output(self, filter_pattern: str | None = None) -> list[str]:
        """获取自上次检查以来的新增输出内容，还可选择通过正则表达式对这些内容进行过滤"""
        new_lines = self.output_lines[self.last_read_index :]
        self.last_read_index = len(self.output_lines)

        if filter_pattern:
            try:
                pattern = re.compile(filter_pattern)
                new_lines = [line for line in new_lines if pattern.search(line)]
            except re.error:
                # Invalid regex, return all lines
                pass

        return new_lines

    def update_status(self, is_alive: bool, exit_code: int | None = None):
        """更新进程状态."""
        if not is_alive:
            self.status = "completed" if exit_code == 0 else "failed"
            self.exit_code = exit_code
        else:
            self.status = "running"

    async def terminate(self):
        """终止后台进程."""
        if self.process.returncode is None:
            self.process.terminate()
            try:
                await asyncio.wait_for(self.process.wait(), timeout=5)
            except asyncio.TimeoutError:
                self.process.kill()
        self.status = "terminated"
        self.exit_code = self.process.returncode


class BackgroundShellManager:
    """管理全部的后台shell进程."""

    _shells: dict[str, BackgroundShell] = {}
    _monitor_tasks: dict[str, asyncio.Task] = {}

    @classmethod
    def add(cls, shell: BackgroundShell) -> None:
        """添加一个后台shell进程"""
        cls._shells[shell.bash_id] = shell

    @classmethod
    def get(cls, bash_id: str) -> BackgroundShell | None:
        """通过ID获得后台shell"""
        return cls._shells.get(bash_id)

    @classmethod
    def get_available_ids(cls) -> list[str]:
        """获取全部可用的shell id."""
        return list(cls._shells.keys())

    @classmethod
    def _remove(cls, bash_id: str) -> None:
        """删除一个后台shell（仅内部使用）"""
        if bash_id in cls._shells:
            del cls._shells[bash_id]

    @classmethod
    async def start_monitor(cls, bash_id: str) -> None:
        """开始监控shell的输出"""
        shell = cls.get(bash_id)
        if not shell:
            return

        async def monitor():
            try:
                process = shell.process
                # 持续读输出，直到进程终止
                while process.returncode is None:
                    try:
                        if process.stdout:
                            line = await asyncio.wait_for(process.stdout.readline(), timeout=0.1)
                            if line:
                                decoded_line = line.decode("utf-8", errors="replace").rstrip("\n")
                                shell.add_output(decoded_line)
                            else:
                                break
                    except asyncio.TimeoutError:
                        await asyncio.sleep(0.1)
                        continue
                    except Exception:
                        await asyncio.sleep(0.1)
                        continue

                # 进程终止，等待退出代码
                try:
                    returncode = await process.wait()
                except Exception:
                    returncode = -1

                # 更新状态
                shell.update_status(is_alive=False, exit_code=returncode)

            except Exception as e:
                if bash_id in cls._shells:
                    cls._shells[bash_id].status = "error"
                    cls._shells[bash_id].add_output(f"Monitor error: {str(e)}")
            finally:
                if bash_id in cls._monitor_tasks:
                    del cls._monitor_tasks[bash_id] # 删除shell

        task = asyncio.create_task(monitor())
        cls._monitor_tasks[bash_id] = task

    @classmethod
    def _cancel_monitor(cls, bash_id: str) -> None:
        """取消并且移除一个监控任务（仅内部使用）"""
        if bash_id in cls._monitor_tasks:
            task = cls._monitor_tasks[bash_id]
            if not task.done():
                task.cancel()
            del cls._monitor_tasks[bash_id]

    @classmethod
    async def terminate(cls, bash_id: str) -> BackgroundShell:
        """终止一个后台shell，清空全部资源.

        Args:
            bash_id: The unique identifier of the background shell

        Returns:
            The terminated BackgroundShell object

        Raises:
            ValueError: If shell not found
        """
        shell = cls.get(bash_id)
        if not shell:
            raise ValueError(f"Shell not found: {bash_id}")

        # 终止进程
        await shell.terminate()

        # 取消监控任务，从manager中移除
        cls._cancel_monitor(bash_id)
        cls._remove(bash_id)

        return shell


class BashTool(Tool):
    """执行shell命令.
    
    自动检测操作系统类别并使用正确的shell命令：
    - Windows: PowerShell
    - Unix/Linux/macOS: bash
    """

    def __init__(self):
        """根据操作系统类别类别初始化BashTool"""
        self.is_windows = platform.system() == "Windows"
        self.shell_name = "PowerShell" if self.is_windows else "bash"

    # 语法糖，将方法转化为成员变量，通过 实例.name调用
    @property
    def name(self) -> str:
        return "bash"

    @property
    def description(self) -> str:
        shell_examples = {
            "Windows": """Execute PowerShell commands in foreground or background.

For terminal operations like git, npm, docker, etc. DO NOT use for file operations - use specialized tools.

Parameters:
  - command (required): PowerShell command to execute
  - timeout (optional): Timeout in seconds (default: 120, max: 600) for foreground commands
  - run_in_background (optional): Set true for long-running commands (servers, etc.)

Tips:
  - Quote file paths with spaces: cd "My Documents"
  - Chain dependent commands with semicolon: git add . ; git commit -m "msg"
  - Use absolute paths instead of cd when possible
  - For background commands, monitor with bash_output and terminate with bash_kill

Examples:
  - git status
  - npm test
  - python -m http.server 8080 (with run_in_background=true)""",
            "Unix": """Execute bash commands in foreground or background.

For terminal operations like git, npm, docker, etc. DO NOT use for file operations - use specialized tools.

Parameters:
  - command (required): Bash command to execute
  - timeout (optional): Timeout in seconds (default: 120, max: 600) for foreground commands
  - run_in_background (optional): Set true for long-running commands (servers, etc.)

Tips:
  - Quote file paths with spaces: cd "My Documents"
  - Chain dependent commands with &&: git add . && git commit -m "msg"
  - Use absolute paths instead of cd when possible
  - For background commands, monitor with bash_output and terminate with bash_kill

Examples:
  - git status
  - npm test
  - python3 -m http.server 8080 (with run_in_background=true)"""
        }
        return shell_examples["Windows"] if self.is_windows else shell_examples["Unix"]

    @property
    def parameters(self) -> dict[str, Any]:
        # 对有空格的文件路径用双引号包裹
        cmd_desc = f"The {self.shell_name} command to execute. Quote file paths with spaces using double quotes."
        return {
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "description": cmd_desc,
                },
                "timeout": {
                    "type": "integer",
                    "description": "Optional: Timeout in seconds (default: 120, max: 600). Only applies to foreground commands.",
                    "default": 120,
                },
                "run_in_background": {
                    "type": "boolean",

                    # 设置为true可以让命令后台运行，此配置适用于服务器这类需要长时间运行的命令，你可以通过 bash_output 工具监控其输出内容
                    "description": "Optional: Set to true to run the command in the background. Use this for long-running commands like servers. You can monitor output using bash_output tool.",
                    "default": False,
                },
            },
            "required": ["command"],
        }

    async def execute(
        self,
        command: str,
        timeout: int = 120,
        run_in_background: bool = False,
    ) -> ToolResult:
        """执行命令（可后台执行）.

        Args:
            command: The shell command to execute
            timeout: Timeout in seconds (default: 120, max: 600)
            run_in_background: Set true to run command in background

        Returns:
            BashExecutionResult with command output and status
        """

        try:
            # 校验时间
            if timeout > 600:
                timeout = 600
            elif timeout < 1:
                timeout = 120

            # 准备具体的shell执行命令
            if self.is_windows:
                # Windows: Use PowerShell with appropriate encoding
                shell_cmd = ["powershell.exe", "-NoProfile", "-Command", command]
            else:
                # Unix/Linux/macOS: Use bash
                shell_cmd = command

            if run_in_background:
                # 后台执行：创建独立隔离的进程
                bash_id = str(uuid.uuid4())[:8]

                # 执行后台命令，结合 stdout/stderr
                if self.is_windows:
                    process = await asyncio.create_subprocess_exec(
                        *shell_cmd,
                        stdout=asyncio.subprocess.PIPE,
                        stderr=asyncio.subprocess.STDOUT,
                    )
                else:
                    process = await asyncio.create_subprocess_shell(
                        shell_cmd,
                        stdout=asyncio.subprocess.PIPE,
                        stderr=asyncio.subprocess.STDOUT,
                    )

                # 创建后台shell并添加到管理器里面
                bg_shell = BackgroundShell(bash_id=bash_id, command=command, process=process, start_time=time.time())
                BackgroundShellManager.add(bg_shell)

                # 开启监控任务
                await BackgroundShellManager.start_monitor(bash_id)

                # 立即返回
                message = f"Command started in background. Use bash_output to monitor (bash_id='{bash_id}')."
                formatted_content = f"{message}\n\nCommand: {command}\nBash ID: {bash_id}"

                return BashOutputResult(
                    success=True,
                    content=formatted_content,
                    stdout=f"Background command started with ID: {bash_id}",
                    stderr="",
                    exit_code=0,
                    bash_id=bash_id,
                )

            else:
                # 前台执行，创建独立隔离进程
                if self.is_windows:
                    process = await asyncio.create_subprocess_exec(
                        *shell_cmd,
                        stdout=asyncio.subprocess.PIPE,
                        stderr=asyncio.subprocess.PIPE,
                    )
                else:
                    process = await asyncio.create_subprocess_shell(
                        shell_cmd,
                        stdout=asyncio.subprocess.PIPE,
                        stderr=asyncio.subprocess.PIPE,
                    )

                try:
                    stdout, stderr = await asyncio.wait_for(process.communicate(), timeout=timeout)
                except asyncio.TimeoutError:
                    process.kill()
                    error_msg = f"Command timed out after {timeout} seconds"
                    return BashOutputResult(
                        success=False,
                        error=error_msg,
                        stdout="",
                        stderr=error_msg,
                        exit_code=-1,
                    )

                # 解码输出
                stdout_text = stdout.decode("utf-8", errors="replace")
                stderr_text = stderr.decode("utf-8", errors="replace")

                # 创建结果（内容自动被model_validator格式化）
                is_success = process.returncode == 0
                error_msg = None
                if not is_success:
                    error_msg = f"Command failed with exit code {process.returncode}"
                    if stderr_text:
                        error_msg += f"\n{stderr_text.strip()}"

                return BashOutputResult(
                    success=is_success,
                    error=error_msg,
                    stdout=stdout_text,
                    stderr=stderr_text,
                    exit_code=process.returncode or 0,
                )

        except Exception as e:
            return BashOutputResult(
                success=False,
                error=str(e),
                stdout="",
                stderr=str(e),
                exit_code=-1,
            )


class BashOutputTool(Tool):
    """从后台shell中获取输出"""

    @property
    def name(self) -> str:
        return "bash_output"

    @property
    def description(self) -> str:
        return """Retrieves output from a running or completed background bash shell.

        - Takes a bash_id parameter identifying the shell
        - Always returns only new output since the last check
        - Returns stdout and stderr output along with shell status
        - Supports optional regex filtering to show only lines matching a pattern
        - Use this tool when you need to monitor or check the output of a long-running shell
        - Shell IDs can be found using the bash tool with run_in_background=true

        Process status values:
          - "running": Still executing
          - "completed": Finished successfully
          - "failed": Finished with error
          - "terminated": Was terminated
          - "error": Error occurred

        Example: bash_output(bash_id="abc12345")"""

    @property
    def parameters(self) -> dict[str, Any]:
        return {
            "type": "object",
            "properties": {
                "bash_id": {
                    "type": "string",
                    "description": "The ID of the background shell to retrieve output from. Shell IDs are returned when starting a command with run_in_background=true.",
                },
                "filter_str": {
                    "type": "string",
                    "description": "Optional regular expression to filter the output lines. Only lines matching this regex will be included in the result. Any lines that do not match will no longer be available to read.",
                },
            },
            "required": ["bash_id"],
        }

    async def execute(
        self,
        bash_id: str,
        filter_str: str | None = None,
    ) -> BashOutputResult:
        """Retrieve output from background shell.

        Args:
            bash_id: The unique identifier of the background shell
            filter_str: Optional regex pattern to filter output lines

        Returns:
            BashOutputResult with shell output including stdout, stderr, status, and success flag
        """

        try:
            # Get background shell from manager
            bg_shell = BackgroundShellManager.get(bash_id)
            if not bg_shell:
                available_ids = BackgroundShellManager.get_available_ids()
                return BashOutputResult(
                    success=False,
                    error=f"Shell not found: {bash_id}. Available: {available_ids or 'none'}",
                    stdout="",
                    stderr="",
                    exit_code=-1,
                )

            # Get new output
            new_lines = bg_shell.get_new_output(filter_pattern=filter_str)
            stdout = "\n".join(new_lines) if new_lines else ""

            return BashOutputResult(
                success=True,
                stdout=stdout,
                stderr="",  # Background shells combine stdout/stderr
                exit_code=bg_shell.exit_code if bg_shell.exit_code is not None else 0,
                bash_id=bash_id,
            )

        except Exception as e:
            return BashOutputResult(
                success=False,
                error=f"Failed to get bash output: {str(e)}",
                stdout="",
                stderr=str(e),
                exit_code=-1,
            )


class BashKillTool(Tool):
    """终止一个正在运行的后台shell"""

    @property
    def name(self) -> str:
        return "bash_kill"

    @property
    def description(self) -> str:
        return """Kills a running background bash shell by its ID.

        - Takes a bash_id parameter identifying the shell to kill
        - Attempts graceful termination (SIGTERM) first, then forces (SIGKILL) if needed
        - Returns the final status and any remaining output before termination
        - Cleans up all resources associated with the shell
        - Use this tool when you need to terminate a long-running shell
        - Shell IDs can be found using the bash tool with run_in_background=true

        Example: bash_kill(bash_id="abc12345")"""

    @property
    def parameters(self) -> dict[str, Any]:
        return {
            "type": "object",
            "properties": {
                "bash_id": {
                    "type": "string",
                    "description": "The ID of the background shell to terminate. Shell IDs are returned when starting a command with run_in_background=true.",
                },
            },
            "required": ["bash_id"],
        }

    async def execute(self, bash_id: str) -> BashOutputResult:
        """Terminate a background shell process.

        Args:
            bash_id: The unique identifier of the background shell to terminate

        Returns:
            BashOutputResult with termination status and remaining output
        """

        try:
            # Get remaining output before termination
            bg_shell = BackgroundShellManager.get(bash_id)
            if bg_shell:
                remaining_lines = bg_shell.get_new_output()
            else:
                remaining_lines = []

            # Terminate through manager (handles all cleanup)
            bg_shell = await BackgroundShellManager.terminate(bash_id)

            # Get remaining output
            stdout = "\n".join(remaining_lines) if remaining_lines else ""

            return BashOutputResult(
                success=True,
                stdout=stdout,
                stderr="",
                exit_code=bg_shell.exit_code if bg_shell.exit_code is not None else 0,
                bash_id=bash_id,
            )

        except ValueError as e:
            # Shell not found
            available_ids = BackgroundShellManager.get_available_ids()
            return BashOutputResult(
                success=False,
                error=f"{str(e)}. Available: {available_ids or 'none'}",
                stdout="",
                stderr=str(e),
                exit_code=-1,
            )
        except Exception as e:
            return BashOutputResult(
                success=False,
                error=f"Failed to terminate bash shell: {str(e)}",
                stdout="",
                stderr=str(e),
                exit_code=-1,
            )

package com.chatbi.agent.tools.bash;

import com.chatbi.agent.tools.ToolResult;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Bash 命令执行结果，包含分离的 stdout 和 stderr
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BashOutputResult extends ToolResult {
    /**
     * 命令的标准输出
     */
    private String stdout = "";
    
    /**
     * 命令的标准错误输出
     */
    private String stderr = "";
    
    /**
     * 命令的退出码
     */
    private int exitCode;
    
    /**
     * Shell 进程 ID（仅在 run_in_background=true 时存在）
     */
    private String bashId;
    
    /**
     * 自动格式化内容
     */
    public void formatContent() {
        StringBuilder output = new StringBuilder();
        
        if (stdout != null && !stdout.isEmpty()) {
            output.append(stdout);
        }
        
        if (stderr != null && !stderr.isEmpty()) {
            output.append("\n[stderr]:\n").append(stderr);
        }
        
        if (bashId != null && !bashId.isEmpty()) {
            output.append("\n[bash_id]:\n").append(bashId);
        }
        
        if (exitCode != 0) {
            output.append("\n[exit_code]:\n").append(exitCode);
        }
        
        if (output.length() == 0) {
            output.append("(no output)");
        }
        
        setContent(output.toString());
    }
}


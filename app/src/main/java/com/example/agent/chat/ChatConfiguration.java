package com.example.agent.chat;

import org.springaicommunity.agent.tools.ShellTools;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
class ChatConfiguration {

    /**
     * 每次对话默认注入的 Bash 工具（还有查看后台输出的 BashOutput、结束后台进程的 KillShell）。
     * 命令以服务进程的身份在 {@code chat.workspace} 目录下执行，没有进程隔离，所以 Bash 默认每次都要人工批准。
     */
    @Bean
    ShellTools shellTools(ChatProperties properties) throws IOException {
        Path workspace = Files.createDirectories(properties.workspace().toAbsolutePath().normalize());
        return ShellTools.builder().workingDirectory(workspace).build();
    }
}

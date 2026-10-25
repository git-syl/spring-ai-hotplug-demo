package com.example.agent.plugin;

import com.example.agent.plugin.api.AgentPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 插件类加载器的三条规则：契约与 Spring AI 由宿主共享、宿主内部类不可见、父优先。 */
class PluginClassLoaderTest {

    @TempDir Path directory;

    @Test
    void sharesTheContractAndSpringAiWithTheHostAndLoadsThePluginsOwnClasses() throws Exception {
        Path jar = TestPlugins.echoJar(directory, "acme", "1.0.0", false, "[]");
        try (var loader = new PluginClassLoader("acme@1.0.0", jar, getClass().getClassLoader())) {
            // 同一个 Class 对象：宿主可以把插件对象当作 AgentPlugin、ToolCallback 使用。
            assertThat(loader.loadClass(AgentPlugin.class.getName())).isSameAs(AgentPlugin.class);
            assertThat(loader.loadClass(ToolCallback.class.getName())).isSameAs(ToolCallback.class);

            Class<?> plugin = loader.loadClass("test.acme.Plugin");
            assertThat(plugin.getClassLoader()).isSameAs(loader);
            assertThat(AgentPlugin.class.isAssignableFrom(plugin)).isTrue();
        }
    }

    @Test
    void refusesHostInternalClassesEvenWhenTheHostHasThem() throws Exception {
        Path jar = TestPlugins.echoJar(directory, "acme", "1.0.0", false, "[]");
        try (var loader = new PluginClassLoader("acme@1.0.0", jar, getClass().getClassLoader())) {
            for (String internal : new String[]{PluginManager.class.getName(), "com.example.agent.chat.ChatController"}) {
                assertThatThrownBy(() -> loader.loadClass(internal))
                        .isInstanceOf(ClassNotFoundException.class)
                        .hasMessageContaining("internal to the host");
            }
        }
    }

    @Test
    void delegatesParentFirstSoTheHostsCopyOfALibraryWins() throws Exception {
        // 插件自带了一个 org.slf4j.LoggerFactory，加载到的仍然是宿主的那一个。
        Path jar = TestPlugins.jar(directory, TestPlugins.echoManifest("acme", "1.0.0", false, "[]"), Map.of(
                "test.acme.Plugin", TestPlugins.echoPlugin("acme"),
                "org.slf4j.LoggerFactory", "package org.slf4j; public class LoggerFactory { }"));
        try (var loader = new PluginClassLoader("acme@1.0.0", jar, getClass().getClassLoader())) {
            assertThat(loader.loadClass("org.slf4j.LoggerFactory")).isSameAs(org.slf4j.LoggerFactory.class);
        }
    }
}

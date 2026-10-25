#!/usr/bin/env bash
# 类加载动手实验：编译并运行 Lab（类加载器的六组实验）和 InitQuiz（static 块何时执行的十道判断题）。
# 需要 JDK 21 及以上（在 JDK 25 上验证过）；Windows 可以在 Git Bash 里运行，或者直接运行 run.cmd。
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out
mkdir -p out/plugin/META-INF/services

# 1. 编译宿主：契约 Greeter + 实验主程序 Lab
javac -encoding UTF-8 -d out/host host/Greeter.java host/Lab.java
# 2. 编译插件：编译时依赖契约（相当于 Maven 的 provided），但不把契约打进插件
javac -encoding UTF-8 -cp out/host -d out/plugin plugin/Helper.java plugin/HelloGreeter.java
# 3. 模拟“插件自己也打包了一份契约”，供实验 4b 的子优先加载器使用
javac -encoding UTF-8 -d out/plugin host/Greeter.java
# 4. 用 ServiceLoader 的方式声明实现
echo lab.plugin.HelloGreeter > out/plugin/META-INF/services/lab.Greeter
# 5. 运行：类路径上只有宿主，插件类只能通过实验创建的类加载器找到
java -Dstdout.encoding=UTF-8 -cp out/host lab.Lab out/plugin

echo
javac -encoding UTF-8 -d out/quiz quiz/InitQuiz.java
java -Dstdout.encoding=UTF-8 -cp out/quiz InitQuiz

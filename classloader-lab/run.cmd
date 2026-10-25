@echo off
rem Class-loading lab: builds and runs Lab (six class loader experiments) and InitQuiz (when static blocks run).
rem Requires JDK 21 or later (verified on JDK 25).
chcp 65001 > nul
cd /d "%~dp0"
if exist out rmdir /s /q out
mkdir out\plugin\META-INF\services

rem 1. host: the contract Greeter and the experiments in Lab
javac -encoding UTF-8 -d out\host host\Greeter.java host\Lab.java || exit /b 1
rem 2. plugin: compiled against the contract (like Maven's provided scope) without bundling it
javac -encoding UTF-8 -cp out\host -d out\plugin plugin\Helper.java plugin\HelloGreeter.java || exit /b 1
rem 3. a copy of the contract inside the plugin, for the child-first loader of experiment 4b
javac -encoding UTF-8 -d out\plugin host\Greeter.java || exit /b 1
rem 4. declare the implementation the ServiceLoader way
echo lab.plugin.HelloGreeter> out\plugin\META-INF\services\lab.Greeter
rem 5. run: only the host is on the class path; plugin classes are reachable only through the lab's class loaders
java -Dstdout.encoding=UTF-8 -cp out\host lab.Lab out\plugin || exit /b 1

echo.
javac -encoding UTF-8 -d out\quiz quiz\InitQuiz.java || exit /b 1
java -Dstdout.encoding=UTF-8 -cp out\quiz InitQuiz

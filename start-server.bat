@echo off
rem Hearthold dev loop: build the plugin, copy it into the local test server (run\) and start it in THIS console.
rem The window stays open: type server commands here, "stop" shuts the server down.
rem Requires JDK 25 (JAVA_HOME or java on PATH) and a Paper server jar at run\paper.jar.
setlocal
chcp 65001 >nul
set "ROOT=%~dp0"
set "JAR=hearthold-0.1.0-SNAPSHOT.jar"
set "JAVA=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"

cd /d "%ROOT%"
echo === Building plugin ===
call gradlew.bat build
if errorlevel 1 (
    echo Build failed, server not started.
    pause
    exit /b 1
)

if not exist "%ROOT%run\paper.jar" (
    echo run\paper.jar not found. Download Paper from https://papermc.io/downloads/paper and save it as run\paper.jar
    pause
    exit /b 1
)

echo === Copying plugin to run\plugins ===
if not exist "%ROOT%run\plugins" mkdir "%ROOT%run\plugins"
copy /y "%ROOT%build\libs\%JAR%" "%ROOT%run\plugins\%JAR%" >nul

echo === Starting server (type stop to shut down) ===
cd /d "%ROOT%run"
"%JAVA%" -Xms1G -Xmx2G -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar paper.jar --nogui
echo Server stopped.
pause

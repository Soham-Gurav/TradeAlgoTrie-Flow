@echo off
echo ====================================================================
echo      COMPILING & LAUNCHING ADVANCED ALGORITHM JAVA REST API (PORT 8080)
echo ====================================================================
cd /d %~dp0backend
if not exist "bin" mkdir bin
javac -d bin src\algo\trie\*.java src\algo\flow\*.java src\algo\bloom\*.java src\algo\stats\*.java src\algo\test\*.java src\algo\api\*.java
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Java compilation failed.
    pause
    exit /b %ERRORLEVEL%
)
echo [SUCCESS] Compilation complete. Launching AlgoApiServer...
java -cp bin algo.api.AlgoApiServer
pause


@echo off
chcp 65001 > nul

call build.bat
if %ERRORLEVEL% neq 0 (
    echo Build failed.
    exit /b %ERRORLEVEL%
)

echo Running Random Search...
java -Dfile.encoding=UTF-8 -cp "bin;jar\vdmj-4.6.0.jar" com.fujitsu.robot.ArsMain %*

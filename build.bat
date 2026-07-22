@echo off
chcp 65001 > nul
if not exist bin mkdir bin
if not exist bin\com\fujitsu\robot mkdir bin\com\fujitsu\robot

REM Copy precompiled class files to bin directory
copy /Y src\main\java\com\fujitsu\robot\*.class bin\com\fujitsu\robot\ > nul

REM Clean only newly built class files
del /Q bin\com\fujitsu\robot\Ars*.class >nul 2>&1

echo Compiling Java Random Search...
javac -d bin -cp "jar\vdmj-4.6.0.jar;src\main\java" -encoding UTF-8 src\main\java\com\fujitsu\robot\ArsExplorer.java src\main\java\com\fujitsu\robot\ArsMain.java src\main\java\com\fujitsu\robot\ArsVDMController.java src\main\java\com\fujitsu\robot\StringValue.java

if %ERRORLEVEL% EQU 0 (
    echo Compilation Successful.
) else (
    echo Compilation Failed.
    exit /b 1
)

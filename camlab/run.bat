@echo off
rem Камерная лаборатория: компиляция и запуск одной командой.
rem Компилирует и свои исходники, и камерные классы мода (см. -sourcepath),
rem поэтому правка ShotPlanner в моде видна здесь после обычного перезапуска.
chcp 65001 >nul
cd /d %~dp0
set JDK=C:\Users\psxrly\.jdks\openjdk-26.0.1
if not exist "%JDK%\bin\javac.exe" (
    echo Не найден JDK: %JDK% — поправьте путь в run.bat
    exit /b 1
)
"%JDK%\bin\javac" -encoding UTF-8 -cp lib\gson-2.10.1.jar -sourcepath "..\src\main\java;src" -d out src\camlab\*.java
if errorlevel 1 exit /b 1
echo.
"%JDK%\bin\java" -cp "out;lib\gson-2.10.1.jar" camlab.CamLab

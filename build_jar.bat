@echo off
chcp 65001 >nul
setlocal

cd /d "%~dp0"
if errorlevel 1 goto failure

call mvn clean package
if errorlevel 1 goto failure

if not exist "target\evocell-lab-1.0-SNAPSHOT.jar" (
    echo ОШИБКА: после сборки не найден target\evocell-lab-1.0-SNAPSHOT.jar
    goto failure
)

copy /Y "target\evocell-lab-1.0-SNAPSHOT.jar" "EvoCell_Laboratory_2.0.jar" >nul
if errorlevel 1 goto failure

echo.
echo Сборка успешно завершена:
echo %~dp0EvoCell_Laboratory_2.0.jar
exit /b 0

:failure
echo.
echo ОШИБКА: сборка или копирование JAR завершились неудачно.
pause
exit /b 1

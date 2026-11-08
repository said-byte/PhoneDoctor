@echo off
rem Сборка и установка Phone Doctor на подключённый телефон.
rem Ничего не устанавливает в систему: всё лежит в .toolchain\

setlocal
set "PROJECT=%~dp0"
set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.11"
set "ANDROID_HOME=%PROJECT%.toolchain\sdk"

cd /d "%PROJECT%"

echo [1/2] Сборка APK...
call gradlew.bat assembleDebug --no-daemon
if errorlevel 1 (
    echo.
    echo Ошибка сборки.
    exit /b 1
)

echo [2/2] Установка на телефон...
"%ANDROID_HOME%\platform-tools\adb.exe" install -r "app\build\outputs\apk\debug\app-debug.apk"
if errorlevel 1 (
    echo.
    echo Телефон не подключён или отладка по USB выключена.
    exit /b 1
)

echo.
echo Готово.
endlocal

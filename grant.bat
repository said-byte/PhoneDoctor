@echo off
rem Восстановление Shizuku-гранта для Phone Doctor после полной переустановки.
rem Нужно: телефон подключен по USB, отладка включена, Shizuku-сервер умеет запускаться.
setlocal enabledelayedexpansion

set ADB=%~dp0.toolchain\sdk\platform-tools\adb.exe
if not exist "%ADB%" set ADB=adb

echo [1/5] Проверяю телефон...
"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
    echo   Телефон не подключен. Подключи USB и разреши отладку.
    pause & exit /b 1
)

echo [2/5] Смотрю uid пакета Phone Doctor...
set UID=
"%ADB%" shell dumpsys package com.phonedoctor > "%TEMP%\pd_uid.txt" 2>nul
for /f "tokens=1" %%u in ('findstr /c:"uid=10" "%TEMP%\pd_uid.txt"') do (
    if not defined UID set UID=%%u
)
set UID=!UID:~4!
if not defined UID (
    echo   Приложение не установлено. Сначала установи APK.
    pause & exit /b 1
)
echo   uid=!UID!

echo [3/5] Записываю грант в конфиг сервера...
set JSON={"version": 2, "packages": [{"uid": !UID!, "flags": 2, "packages": ["com.phonedoctor"]}]}
echo !JSON!>"%TEMP%\shizuku.json"
"%ADB%" push "%TEMP%\shizuku.json" /data/local/tmp/shizuku.json >nul
"%ADB%" shell cp /data/local/tmp/shizuku.json /data/user_de/0/com.android.shell/shizuku.json
"%ADB%" shell cat /data/user_de/0/com.android.shell/shizuku.json

echo [4/5] Перезапускаю сервер Shizuku...
set PID=
for /f "tokens=1" %%p in ('"%ADB%" shell ps -A ^| findstr shizuku_server') do if not defined PID set PID=%%p
if defined PID "%ADB%" shell kill !PID! >nul 2>&1

set APKPATH=
for /f "tokens=2 delims=:" %%p in ('"%ADB%" shell pm path moe.shizuku.privileged.api') do set APKPATH=%%p
if not defined APKPATH (
    echo   Shizuku не установлен. Установи apk из .toolchain и запусти сервер.
    pause & exit /b 1
)
set APKDIR=!APKPATH:base.apk=!
"%ADB%" shell "!APKDIR!lib/arm64/libshizuku.so"
timeout /t 6 /nobreak >nul

echo [5/5] Запускаю Phone Doctor...
"%ADB%" shell am start -n com.phonedoctor/.ui.MainActivity >nul 2>&1
echo Готово. Открой Phone Doctor: он сам включит службу и отсканирует.
pause

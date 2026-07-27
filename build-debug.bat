@echo off
setlocal

set "PROJECT_ROOT=%~dp0"
set "PROJECT_JDK=%PROJECT_ROOT%.gradle\jdks\jdk-17.0.20+8"

if exist "%PROJECT_JDK%\bin\java.exe" set "JAVA_HOME=%PROJECT_JDK%"
if not defined JAVA_HOME (
    echo ERROR: JDK 17 is required. Set JAVA_HOME to a JDK 17 installation. 1>&2
    exit /b 1
)

if not defined ANDROID_SDK_ROOT set "ANDROID_SDK_ROOT=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_USER_HOME set "ANDROID_USER_HOME=%USERPROFILE%\.android"
set "GRADLE_USER_HOME=%PROJECT_ROOT%.gradle"
set "GRADLE_OPTS=%GRADLE_OPTS% -Duser.home=%USERPROFILE%"

call "%PROJECT_ROOT%gradlew.bat" assembleDebug %*
exit /b %ERRORLEVEL%

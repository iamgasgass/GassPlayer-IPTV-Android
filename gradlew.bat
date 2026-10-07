@echo off
setlocal
set GRADLE_VERSION=9.6.0
if not "%GRADLE_HOME%"=="" if exist "%GRADLE_HOME%\bin\gradle.bat" call "%GRADLE_HOME%\bin\gradle.bat" %* & exit /b %ERRORLEVEL%
where gradle >nul 2>nul
if %ERRORLEVEL% EQU 0 call gradle %* & exit /b %ERRORLEVEL%
set CACHE=%USERPROFILE%\.gradle\wrapper\dists\gradle-%GRADLE_VERSION%-bin
set DIST=%CACHE%\gradle-%GRADLE_VERSION%\bin\gradle.bat
if not exist "%DIST%" (
  if not exist "%CACHE%" mkdir "%CACHE%"
  set TMP=%CACHE%\gradle-%GRADLE_VERSION%-bin.zip
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%TMP%'"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Force '%TMP%' '%CACHE%'"
)
call "%DIST%" %*
endlocal

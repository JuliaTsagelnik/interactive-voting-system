@echo off
chcp 65001 >nul

set "JAVA=E:\Program Files\bin\java.exe"
set "SERVER_JAR=%~dp0voting-server\target\voting-server-1.0-SNAPSHOT-jar-with-dependencies.jar"
set "CLIENT_JAR=%~dp0voting-client\target\voting-client-1.0-SNAPSHOT-jar-with-dependencies.jar"

start "Voting Server" /D "%~dp0" "%JAVA%" -jar "%SERVER_JAR%"

timeout /t 3 /nobreak >nul

start "Voting Client 1" "%JAVA%" -jar "%CLIENT_JAR%"
start "Voting Client 2" "%JAVA%" -jar "%CLIENT_JAR%"
start "Voting Client 3" "%JAVA%" -jar "%CLIENT_JAR%"
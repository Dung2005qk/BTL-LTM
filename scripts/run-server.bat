@echo off
cd /d "%~dp0.."
java -Dfile.encoding=UTF-8 -cp target\geoduel.jar com.ltm.geoduel.server.ServerMain config.properties
pause

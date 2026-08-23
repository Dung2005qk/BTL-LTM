@echo off
cd /d "%~dp0.."
start "GeoDuel Client" javaw -Dfile.encoding=UTF-8 -Xmx1024m -cp target\geoduel.jar com.ltm.geoduel.client.ClientMain config.properties

@echo off
rem Kiem thu tich hop E2E: khoi dong server test (cong 5556, DB geoduel_test) va cho bot thi dau
cd /d "%~dp0.."
java -Dfile.encoding=UTF-8 -cp target\geoduel.jar com.ltm.geoduel.sim.IntegrationSim config-test.properties
pause

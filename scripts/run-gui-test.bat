@echo off
rem Kiem thu khoi giao dien: mo 2 cua so client that, tu choi 1 tran va chup anh man hinh
cd /d "%~dp0.."
java -Dfile.encoding=UTF-8 -Xmx1500m -cp target\geoduel.jar com.ltm.geoduel.sim.GuiSmokeTest config-test.properties target\gui-shots
echo Anh chup o target\gui-shots
pause

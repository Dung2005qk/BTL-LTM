@echo off
rem Thu thap anh dia diem tu KartaView + ghep ban do Viet Nam tu OSM (chay lai se bo qua phan da co)
cd /d "%~dp0.."
call mvn -q package -DskipTests
java -cp target\geoduel.jar com.ltm.geoduel.tools.KartaViewCollector 220 4
java -cp target\geoduel.jar com.ltm.geoduel.tools.VietnamMapBuilder
pause

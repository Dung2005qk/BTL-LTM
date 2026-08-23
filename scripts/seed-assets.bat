@echo off
rem Xu ly anh (xoa EXIF/GPS, resize) va nap dia diem vao DB chinh + DB test, tao tai khoan demo
cd /d "%~dp0.."
java -cp target\geoduel.jar com.ltm.geoduel.tools.AssetSeeder --demo-users config.properties config-test.properties
pause

@echo off
rem Chay client noi toi server o MAY KHAC trong mang LAN.
rem May nay KHONG can MySQL, khong can anh — chi can build xong (mvn package -DskipTests).
cd /d "%~dp0.."
set /p GHOST=Nhap IP may chu (vi du 192.168.0.10):
if "%GHOST%"=="" (
  echo Chua nhap IP.
  pause
  exit /b 1
)
start "GeoDuel Client" javaw -Dfile.encoding=UTF-8 -Xmx1024m -cp target\geoduel.jar com.ltm.geoduel.client.ClientMain config.example.properties %GHOST% 5555

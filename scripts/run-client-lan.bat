@echo off
rem Chay client noi toi server o MAY KHAC trong mang LAN.
rem May nay KHONG can MySQL, khong can anh — chi can co target\geoduel.jar.
cd /d "%~dp0.."

if not exist "target\geoduel.jar" (
  echo.
  echo [LOI] Khong tim thay file "target\geoduel.jar"!
  echo Nguyen nhan: May ban chua build hoac chua co file jar.
  echo Giai phap:
  echo   1. Chay lenh: mvn package -DskipTests
  echo   HOAC
  echo   2. Copy ca thu muc "target" tu may chu sang may nay.
  echo.
  pause
  exit /b 1
)

set /p GHOST=Nhap IP may chu (vi du 10.80.189.198):
if "%GHOST%"=="" (
  echo Chua nhap IP.
  pause
  exit /b 1
)
echo Dang khoi dong GeoDuel Client ket noi toi %GHOST%:5555...
start "GeoDuel Client" javaw -Dfile.encoding=UTF-8 -Xmx1024m -cp target\geoduel.jar com.ltm.geoduel.client.ClientMain config.example.properties %GHOST% 5555


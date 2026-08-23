@echo off
rem Mo cong 5555 tren tuong lua Windows de may khac ket noi vao server GeoDuel.
rem PHAI chay bang quyen Administrator: chuot phai file nay -> Run as administrator.
netsh advfirewall firewall add rule name="GeoDuel Server 5555" dir=in action=allow protocol=TCP localport=5555
if %errorlevel%==0 (
  echo Da mo cong 5555. May khac trong LAN co the ket noi.
) else (
  echo THAT BAI - hay chay lai file nay bang "Run as administrator".
)
pause

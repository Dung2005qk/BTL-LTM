@echo off
rem Xuat du lieu dia diem + tai khoan demo ra database\seed-data.sql (chay lai sau khi thu thap them anh)
cd /d "%~dp0.."
set MYSQLDUMP="C:\Program Files\MySQL\MySQL Server 9.5\bin\mysqldump.exe"
%MYSQLDUMP% -u root -p --no-create-info --skip-comments --complete-insert geoduel locations location_images > database\seed-data.sql
%MYSQLDUMP% -u root -p --no-create-info --skip-comments --complete-insert --where="username LIKE 'demo%%'" geoduel users >> database\seed-data.sql
echo Da ghi database\seed-data.sql
pause

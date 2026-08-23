@echo off
rem Nap du lieu dia diem + tai khoan demo tu database\seed-data.sql vao geoduel va geoduel_test
rem (chay SAU scripts\init-db.bat)
cd /d "%~dp0.."
set MYSQL="C:\Program Files\MySQL\MySQL Server 9.5\bin\mysql.exe"
%MYSQL% -u root -p --default-character-set=utf8mb4 geoduel < database\seed-data.sql
%MYSQL% -u root -p --default-character-set=utf8mb4 geoduel_test < database\seed-data.sql
echo Xong. Kiem tra: SELECT COUNT(*) FROM geoduel.locations;
pause

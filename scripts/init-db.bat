@echo off
rem Tao database geoduel + geoduel_test (chay 1 lan, chay lai an toan)
cd /d "%~dp0.."
"C:\Program Files\MySQL\MySQL Server 9.5\bin\mysql.exe" -u root -p < database\schema.sql
echo Xong. Neu khong co loi phia tren nghia la schema da san sang.
pause

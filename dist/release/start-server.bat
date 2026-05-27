@echo off
echo Starting PaymentSystem Server...
cd /d "%~dp0server"
java -jar server-all.jar
pause

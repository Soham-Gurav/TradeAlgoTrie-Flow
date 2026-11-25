@echo off
echo ====================================================================
echo      LAUNCHING NEXT.JS FRONTEND (PORT 3000)
echo ====================================================================
cd /d %~dp0frontend
npm run dev
pause


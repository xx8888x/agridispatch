@echo off
setlocal
rem Run Android unit tests (client + server debug).
rem The project path contains CJK characters and parentheses, which makes the Gradle test JVM
rem fail with ClassNotFoundException. Map this folder to a spare drive letter with subst,
rem run the tests there, then remove the mapping. See README.md.
rem Usage: run_unit_tests.bat [extra gradle args, e.g. --offline]
set "HERE=%~dp0"
set "HERE=%HERE:~0,-1%"
set "DRV="
for %%d in (T U V W X Y Z) do if not defined DRV if not exist %%d:\ set "DRV=%%d:"
if not defined DRV goto nodrive
subst %DRV% "%HERE%"
if errorlevel 1 goto nodrive
pushd %DRV%\
call "%DRV%\gradlew.bat" testClientDebugUnitTest testServerDebugUnitTest %*
set "RC=%errorlevel%"
popd
subst %DRV% /d
exit /b %RC%

:nodrive
echo [run_unit_tests] No free drive letter (T: to Z:) or subst failed.
exit /b 1

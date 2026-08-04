@echo off
setlocal enabledelayedexpansion

cd /d "%~dp0"

rem ===== Universal NL build script =========================================
rem Mod name comes from this folder and platforms are auto-discovered, so this
rem file is byte-identical for every NL mod and the template. To change build
rem behaviour: edit _mod_template\template\build-jar.bat then run
rem _sync_build_jar.bat in the parent to push it to every mod.
rem =========================================================================

set "PDIR=%~dp0"
set "PDIR=%PDIR:~0,-1%"
for %%I in ("%PDIR%") do set "MODNAME=%%~nxI"
set "NLMOD=%~dp0..\_NL_mod"
set "VERSION_FILE=%~dp0VERSION"

if not exist "%VERSION_FILE%" (
    echo ERROR: Missing version file: %VERSION_FILE%
    exit /b 1
)
set /p MODVER=<"%VERSION_FILE%"
rem First line is CURRENT_VERSION=<ver> (older mods used a bare <ver>); strip the optional label.
for /f "tokens=2 delims==" %%v in ("%MODVER%") do set "MODVER=%%v"
if not defined MODVER (
    echo ERROR: VERSION is empty.
    exit /b 1
)
set "PREVVER="
for /f "tokens=1,* delims==" %%A in ('findstr /b /c:"PREVIOUS_VERSION=" "%VERSION_FILE%"') do (
    set "PREVVER=%%B"
)

echo ============================================
echo  Building %MODNAME%
echo ============================================
echo.

rem --- Require a Java 21+ JVM (fabric-loom 1.15 needs it; 1.20.1 still targets 17 via toolchains) ---
set "JV="
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do (
    if not defined JV set "JV=%%~v"
)
for /f "tokens=1 delims=." %%a in ("%JV%") do set "JMAJOR=%%a"
if defined JMAJOR if %JMAJOR% LSS 21 (
    echo ERROR: This build needs a Java 21 JDK ^(detected Java %JV%^).
    echo        Point JAVA_HOME at a JDK 21, e.g.  set "JAVA_HOME=C:\Path\to\jdk-21"
    echo.
    exit /b 1
)

rem --- Error log: all gradle output goes here; deleted on success, kept on failure ---
set "LOG=%~dp0build-error.log"
if exist "%LOG%" del /q "%LOG%"

rem --- Build every <version>\<loader> project that exists (forge / fabric / neoforge) ---
for /d %%V in (1.*) do (
    for /d %%L in ("%%V\*") do (
        echo === Building %%V\%%~nxL ===
        echo ======== Building %%V\%%~nxL ========>> "%LOG%"
        pushd "%%V\%%~nxL" || goto build_failed
        call gradlew.bat build >> "%LOG%" 2>&1
        set "RC=!ERRORLEVEL!"
        popd
        if not "!RC!"=="0" goto build_failed
        echo.>> "%LOG%"
        echo.
    )
)

echo Collecting jars into dist\ and mirroring into "%NLMOD%" ...
if not exist dist mkdir dist

for /d %%V in (1.*) do (
    for /d %%L in ("%%V\*") do (
        set "LOADER=%%~nxL"
        rem Use the real Minecraft version from gradle.properties (falls back to the folder name).
        set "MCVER=%%V"
        if exist "%%V\%%~nxL\gradle.properties" (
            for /f "tokens=2 delims==" %%m in ('findstr /b /i /c:"minecraft_version=" "%%V\%%~nxL\gradle.properties"') do set "MCVER=%%m"
        )
        for %%F in ("%%V\%%~nxL\build\libs\*.jar") do (
            echo %%~nF| findstr /i /c:"-slim" /c:"-sources" >nul
            if errorlevel 1 (
                rem build\libs accumulates every past version; only collect the jar whose name ends
                rem with the current version, otherwise an alphabetically-later old jar (e.g. -2.1.9)
                rem overwrites the freshly built one under the new version's name.
                echo %%~nF| findstr /e /c:"-%MODVER%" >nul
                if not errorlevel 1 (
                    set "OUT=%MODNAME%-!LOADER!-!MCVER!-!MODVER!.jar"
                    copy /y "%%F" "dist\!OUT!" >nul
                    if not exist "%NLMOD%\!MCVER!\!LOADER!" mkdir "%NLMOD%\!MCVER!\!LOADER!"
                    copy /y "%%F" "%NLMOD%\!MCVER!\!LOADER!\!OUT!" >nul
                )
            )
        )
        if defined PREVVER (
            set "OLDOUT=%MODNAME%-!LOADER!-!MCVER!-!PREVVER!.jar"
            if exist "dist\!OLDOUT!" del /q "dist\!OLDOUT!"
            if exist "%NLMOD%\!MCVER!\!LOADER!\!OLDOUT!" del /q "%NLMOD%\!MCVER!\!LOADER!\!OLDOUT!"
        )
    )
)

del /q "%LOG%" 2>nul

echo.
echo Build complete. Jars in dist\ (also in %NLMOD%\^<mcversion^>\^<loader^>\):
echo.
for %%F in (dist\*.jar) do echo     %%~fF
echo.
exit /b 0

:build_failed
echo.
echo Build FAILED. Full error log saved to:
echo     %LOG%
echo.
echo ----- last 40 lines of build-error.log -----
if exist "%LOG%" powershell -NoProfile -Command "Get-Content -LiteralPath '%LOG%' -Tail 40"
echo --------------------------------------------
echo.
exit /b 1

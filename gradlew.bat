@rem
@rem Gradle startup script for Windows (placeholder)
@rem
@rem If Android Studio is installed, prefer: use Gradle wrapper from the IDE directly.
@rem Otherwise run: gradlew.bat assembleRelease
@rem
@echo off
setlocal
set DIRNAME=%~dp0
if "%DIRNAME%" == "" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

@rem Attempt to use wrapper jar if present, else delegate to local 'gradle' command
if exist "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" (
    java -Xmx3072m -classpath "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
) else (
    gradle %*
)
endlocal

#!/bin/sh
# placeholder gradlew. see gradlew.bat on windows, or open project in Android Studio.
if [ -f "$(dirname "$0")/gradle/wrapper/gradle-wrapper.jar" ]; then
  java -Xmx3072m -classpath "$(dirname "$0")/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
else
  gradle "$@"
fi

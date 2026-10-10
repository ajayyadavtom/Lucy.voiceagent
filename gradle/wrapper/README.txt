The gradle-wrapper.jar will be automatically downloaded on first gradle run.
If it doesn't download automatically, you can:

1. Run: gradlew.bat clean build --no-daemon
2. Or install Gradle manually and use: gradle assembleDebug

The wrapper will bootstrap itself on first execution.

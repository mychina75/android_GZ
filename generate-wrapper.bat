@echo off
setlocal

:: 定义Gradle版本
set GRADLE_VERSION=8.5

:: 创建wrapper目录
mkdir gradle\wrapper 2>nul

:: 下载gradle-wrapper.jar（8.5版本，阿里云镜像）
curl -o gradle\wrapper\gradle-wrapper.jar https://mirrors.aliyun.com/gradle/libs/org/gradle/wrapper/gradle-wrapper/%GRADLE_VERSION%/gradle-wrapper-%GRADLE_VERSION%.jar

:: 生成gradle-wrapper.properties
echo distributionBase=GRADLE_USER_HOME> gradle\wrapper\gradle-wrapper.properties
echo distributionPath=wrapper/dists>> gradle\wrapper\gradle-wrapper.properties
echo distributionUrl=https://mirrors.aliyun.com/gradle/gradle-%GRADLE_VERSION%-bin.zip>> gradle\wrapper\gradle-wrapper.properties
echo zipStoreBase=GRADLE_USER_HOME>> gradle\wrapper\gradle-wrapper.properties
echo zipStorePath=wrapper/dists>> gradle\wrapper\gradle-wrapper.properties

:: 下载gradlew.bat（8.5版本适配）
curl -o gradlew.bat https://raw.githubusercontent.com/gradle/gradle/v%GRADLE_VERSION%/subprojects/plugins/src/main/resources/org/gradle/api/internal/plugins/wrapper/wrapper.bat

:: 赋予执行权限（Windows无需，但确保文件完整）
echo Wrapper文件生成完成！
endlocal
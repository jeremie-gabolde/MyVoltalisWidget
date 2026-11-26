$env:JAVA_HOME = (Get-ChildItem "C:\Program Files\Microsoft\jdk-17*" -Directory | Select-Object -First 1).FullName
& "$env:USERPROFILE\gradle-tools\gradle-8.7\bin\gradle.bat" -p $PSScriptRoot assembleRelease
Write-Host "APK: $PSScriptRoot\app\build\outputs\apk\release\OurVoltalisWidget.apk"

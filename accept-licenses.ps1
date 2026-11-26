$env:JAVA_HOME = (Get-ChildItem "C:\Program Files\Microsoft\jdk-17*" -Directory | Select-Object -First 1).FullName
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
& "$sdk\cmdline-tools\latest\bin\sdkmanager.bat" "--sdk_root=$sdk" --licenses

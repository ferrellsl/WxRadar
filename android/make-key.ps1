# Makes the key WxRadar's Android releases are signed with, once.
# The key goes to C:\Keys (outside this public repository) and keystore.properties
# (ignored by git) tells Gradle where it is and its password.  Back the key up:
# Android installs an update only when it is signed with the same key.
$ErrorActionPreference = 'Stop'
$key = 'C:\Keys\wxradar-release.jks'
if (Test-Path $key) { Write-Host "$key already exists - nothing done."; exit 1 }
$p1 = Read-Host 'Password for the new key (6+ characters)' -AsSecureString
$p2 = Read-Host 'The same again' -AsSecureString
$a = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($p1))
$b = [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($p2))
if ($a -ne $b -or $a.Length -lt 6) { Write-Host 'The passwords differ or are too short - nothing done.'; exit 1 }
New-Item -ItemType Directory -Force (Split-Path $key) | Out-Null
$keytool = 'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe'
& $keytool -genkeypair -keystore $key -alias wxradar -keyalg RSA -keysize 4096 -validity 36500 `
    -storepass $a -keypass $a -dname 'CN=WxRadar, O=ferrellsl'
if ($LASTEXITCODE -ne 0) { exit 1 }
$escaped = $key -replace '\\', '\\'
@"
storeFile=$escaped
storePassword=$a
keyAlias=wxradar
keyPassword=$a
"@ | Set-Content -Encoding ASCII (Join-Path $PSScriptRoot 'keystore.properties')
Write-Host "Made $key and keystore.properties. Back the key and its password up somewhere safe."

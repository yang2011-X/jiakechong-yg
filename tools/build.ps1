# Build the patched 甲壳虫ADB助手 (com.didjdk.adbhelper) APK from the official v1.3.1 base.
#
#   pwsh -File tools\build.ps1 -Apk D:\path\to\beetle-adbhelper-1.3.1.apk
#
# Requirements: JDK 11+ (javac/java), Android build-tools 34 (d8/zipalign/apksigner),
# platforms/android-34/android.jar, apktool 2.11.x, Python 3.8+.
# The script never downloads anything and never touches your original APK.

param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$OutApk = "",
    [string]$ApktoolJar = "C:\Users\yangg\tools\apktool\apktool.jar",
    [string]$Sdk = $env:ANDROID_SDK_ROOT,
    [string]$Jdk = $env:JAVA_HOME,
    [string]$Keystore = "",
    [string]$KsPass = "beetle123",
    [string]$KeyPass = "beetle123",
    [string]$KeyAlias = "beetle"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSCommandPath)   # repo root
$build = Join-Path $root "build"
$work = Join-Path $build "work"

if (-not $Sdk) { throw "set ANDROID_SDK_ROOT or pass -Sdk" }
if (-not $Jdk) { throw "set JAVA_HOME or pass -Jdk" }
if (-not $OutApk) { $OutApk = Join-Path $build "beetle-adbhelper-1.3.1-android16-patched.apk" }

$java = Join-Path $Jdk "bin\java.exe"
$javac = Join-Path $Jdk "bin\javac.exe"
$keytool = Join-Path $Jdk "bin\keytool.exe"
$bt = Join-Path $Sdk "build-tools\34.0.0"
$androidJar = Join-Path $Sdk "platforms\android-34\android.jar"
$python = $env:PYTHON
if (-not $python) {
    foreach ($cand in @("python3", "python", "py")) {
        $src = (Get-Command $cand -ErrorAction SilentlyContinue).Source
        # skip the Microsoft Store stub, which only prints "Python was not found"
        if ($src -and $src -notmatch "WindowsApps") { $python = $src; break }
    }
}
if (-not $python) { throw "python 3 not found; set PYTHON to python.exe" }
foreach ($p in @($java, $javac, $ApktoolJar, $bt, $androidJar)) {
    if (-not (Test-Path $p)) { throw "missing: $p" }
}

if (-not $Keystore) { $Keystore = Join-Path $root ".keystore\patch.keystore" }
if (-not (Test-Path $Keystore)) {
    New-Item -ItemType Directory -Force (Split-Path -Parent $Keystore) | Out-Null
    & $keytool -genkeypair -v -keystore $Keystore -alias $KeyAlias -keyalg RSA -keysize 2048 `
        -validity 10000 -storepass $KsPass -keypass $KeyPass -dname "CN=Beetle Patch, O=Unofficial, C=CN" 2>&1 | Out-Null
    Write-Host "generated keystore $Keystore"
}

if (Test-Path $work) { Remove-Item $work -Recurse -Force }
New-Item -ItemType Directory -Force $work | Out-Null

# 1) decode
Write-Host "[1/7] apktool decode"
& $java -jar $ApktoolJar d $Apk -o (Join-Path $work "dec") -f 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "apktool decode failed" }

# 2) smali patches
Write-Host "[2/7] apply smali patches"
& $python (Join-Path $root "tools\apply_patches.py") (Join-Path $work "dec\smali") `
    (Join-Path $root "patches\beetle-android16.json")
if ($LASTEXITCODE -ne 0) { throw "patch failed" }

# 3) watchdog dex -> classes2.dex
Write-Host "[3/7] build classes2.dex (RcWatch)"
$rcOut = Join-Path $work "rc-classes"
New-Item -ItemType Directory -Force $rcOut | Out-Null
& $javac -encoding UTF-8 -nowarn -parameters -source 8 -target 8 -bootclasspath $androidJar `
    -d $rcOut (Join-Path $root "javarc\com\didjdk\adbhelper\RcWatch.java")
if ($LASTEXITCODE -ne 0) { throw "javac RcWatch failed" }
$rcDex = Join-Path $work "rc-dex"
New-Item -ItemType Directory -Force $rcDex | Out-Null
$rcClass = (Get-ChildItem $rcOut -Recurse -Filter *.class | ForEach-Object { '"' + $_.FullName + '"' }) -join " "
& cmd.exe /c ('"' + $bt + '\d8.bat" --lib "' + $androidJar + '" --min-api 21 --output "' + $rcDex + '" ' + $rcClass)
if (-not (Test-Path (Join-Path $rcDex "classes.dex"))) { throw "d8 RcWatch failed" }

# 4) device-side helper: classes.dex -> beetle_server.apk
Write-Host "[4/7] build beetle_server.apk (AppDump)"
$svOut = Join-Path $work "sv-classes"
New-Item -ItemType Directory -Force $svOut | Out-Null
& $javac -encoding UTF-8 -nowarn -parameters -source 8 -target 8 -bootclasspath $androidJar `
    -d $svOut (Join-Path $root "javaserver\com\beetle\AppDump.java")
if ($LASTEXITCODE -ne 0) { throw "javac AppDump failed" }
$svDex = Join-Path $work "sv-dex"
New-Item -ItemType Directory -Force $svDex | Out-Null
$svClass = (Get-ChildItem $svOut -Recurse -Filter *.class | ForEach-Object { '"' + $_.FullName + '"' }) -join " "
& cmd.exe /c ('"' + $bt + '\d8.bat" --lib "' + $androidJar + '" --min-api 21 --output "' + $svDex + '" ' + $svClass)
if (-not (Test-Path (Join-Path $svDex "classes.dex"))) { throw "d8 AppDump failed" }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$serverApk = Join-Path $work "beetle_server.apk"
if (Test-Path $serverApk) { Remove-Item $serverApk -Force }
[System.IO.Compression.ZipFile]::CreateFromDirectory($svDex, $serverApk)
Copy-Item $serverApk (Join-Path $work "dec\assets\beetle_server.apk") -Force

# 5) apktool build
Write-Host "[5/7] apktool build"
$unsigned = Join-Path $work "unsigned.apk"
& $java -jar $ApktoolJar b (Join-Path $work "dec") -o $unsigned 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "apktool build failed" }

# 6) inject classes2.dex
Write-Host "[6/7] inject classes2.dex"
$withDex = Join-Path $work "with-dex.apk"
$zip = [System.IO.Compression.ZipFile]::Open($unsigned, "Update")
try {
    $old = $zip.GetEntry("classes2.dex")
    if ($old) { $old.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, (Join-Path $rcDex "classes.dex"), "classes2.dex") | Out-Null
} finally { $zip.Dispose() }
Move-Item $unsigned $withDex -Force

# 7) zipalign + sign
Write-Host "[7/7] zipalign + apksigner"
$aligned = Join-Path $work "aligned.apk"
& (Join-Path $bt "zipalign.exe") -f -p 4 $withDex $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }
New-Item -ItemType Directory -Force (Split-Path -Parent $OutApk) | Out-Null
& $java -jar (Join-Path $bt "lib\apksigner.jar") sign --ks $Keystore --ks-pass "pass:$KsPass" `
    --key-pass "pass:$KeyPass" --ks-key-alias $KeyAlias --out $OutApk $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

& $java -jar (Join-Path $bt "lib\apksigner.jar") verify --print-certs $OutApk | Select-Object -First 3
Write-Host ("BUILT: " + $OutApk + "  " + (Get-Item $OutApk).Length + " bytes")

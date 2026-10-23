# //! 对 unsigned release 做对齐、固定证书校验和外部签名；不生成密钥。
param(
    [Parameter(Mandatory)][string]$Apk,
    [Parameter(Mandatory)][string]$Out,
    [Parameter(Mandatory)][string]$Sdk,
    [Parameter(Mandatory)][string]$Java,
    [Parameter(Mandatory)][ValidatePattern('^[a-fA-F0-9]{64}$')][string]$ExpectedCertificate
)
$ErrorActionPreference = 'Stop'
foreach ($name in @('QINGSTUDY_KEYSTORE','QINGSTUDY_KEY_ALIAS','QINGSTUDY_STORE_PASSWORD','QINGSTUDY_KEY_PASSWORD')) {
    if (![Environment]::GetEnvironmentVariable($name, 'Process')) { throw "缺少签名环境变量：$name；不回退开发签名。" }
}
$inputPath = (Resolve-Path -LiteralPath $Apk).Path
$outputPath = [IO.Path]::GetFullPath($Out)
if ($inputPath -eq $outputPath -or (Test-Path -LiteralPath $outputPath)) { throw '输出必须是新的文件，不覆盖源包或已有发行物。' }
$env:JAVA_HOME = $Java
$env:PATH = "$Java/bin;$env:PATH"
$bin = Join-Path $Sdk 'build-tools/35.0.0'
$badging = & "$bin/aapt.exe" dump badging $inputPath
if ($LASTEXITCODE -or ($badging -match 'application-debuggable') -or !($badging -match "package: name='dev.qingstudy.ime'")) { throw '拒绝调试包、错误包名或无法解析的 APK。' }
$cert = & "$Java/bin/keytool.exe" -list -v '-J-Duser.language=en' -keystore $env:QINGSTUDY_KEYSTORE -alias $env:QINGSTUDY_KEY_ALIAS -storepass:env QINGSTUDY_STORE_PASSWORD 2>&1
if ($LASTEXITCODE) { throw '读取签名证书失败；请检查密钥配置。' }
$match = [regex]::Match(($cert -join "`n"), 'SHA256:\s*([A-Fa-f0-9:]{95})')
$actual = $match.Groups[1].Value.Replace(':','').ToLowerInvariant()
if ($actual -ne $ExpectedCertificate.ToLowerInvariant()) { throw '证书与预期不符，拒绝签名。' }
if ($actual -eq 'b5c8d9d5636fa2a5dc18ce91708e3dc1d4d8e25087e1aac69b39ca005c7c220e' -or ($cert -match 'CN=Android Debug')) { throw '拒绝使用开发签名发布。' }
New-Item -ItemType Directory -Force (Split-Path $outputPath) | Out-Null
$aligned = "$outputPath.aligned.partial"
try {
    & "$bin/zipalign.exe" -f -P 16 4 $inputPath $aligned
    if ($LASTEXITCODE) { throw '对齐失败' }
    & "$bin/apksigner.bat" sign --ks $env:QINGSTUDY_KEYSTORE --ks-key-alias $env:QINGSTUDY_KEY_ALIAS --ks-pass env:QINGSTUDY_STORE_PASSWORD --key-pass env:QINGSTUDY_KEY_PASSWORD --out $outputPath $aligned
    if ($LASTEXITCODE) { throw '签名失败' }
    & "$bin/apksigner.bat" verify --verbose --print-certs $outputPath
    if ($LASTEXITCODE) { throw '签名验证失败' }
    & "$bin/zipalign.exe" -c -P 16 4 $outputPath
    if ($LASTEXITCODE) { throw '页对齐验证失败' }
    $hash = (Get-FileHash -LiteralPath $outputPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -LiteralPath "$outputPath.sha256" -Value "$hash  $([IO.Path]::GetFileName($outputPath))" -Encoding ascii
} finally {
    if (Test-Path -LiteralPath $aligned) { Remove-Item -LiteralPath $aligned }
}

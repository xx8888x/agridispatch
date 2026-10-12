<#
  智農派工 DEV 部署（在本機 Windows 執行）：建置後端 jar → 上傳程式、網頁、建表腳本 → 主機上執行 server-deploy.sh
  用法：
    .\deploy.ps1 -HostIp 1.2.3.4                      # 一般部署
    .\deploy.ps1 -HostIp 1.2.3.4 -Setup               # 第一次：先執行 server-setup.sh（安裝套件、資料庫、服務）
    .\deploy.ps1 -HostIp 1.2.3.4 -Accounts <SQL 檔>   # 一併匯入帳號 SQL（AccountSqlTool 產生，上傳後即刪除）
  連線：ssh opc@<IP>，金鑰 ~/.ssh/agri_dev_ed25519（建立主機時貼上的公鑰）
  網域：預設 dev.<IP 以 - 連接>.sslip.io（免費，指向主機 IP）
#>
param(
    [Parameter(Mandatory = $true)][string]$HostIp,
    [string]$Domain = "",
    [string]$User = "opc",
    [string]$Key = "$env:USERPROFILE\.ssh\agri_dev_ed25519",
    [switch]$Setup,
    [string]$Accounts = ""
)
# 原生程式（mvn、ssh、scp、tar）寫到 stderr 的警告在 PowerShell 5.1 會被當成錯誤，改以 $LASTEXITCODE 判斷成敗
$ErrorActionPreference = "Continue"
function Check([string]$what) { if ($LASTEXITCODE -ne 0) { throw "$what 失敗（exit $LASTEXITCODE）" } }
if (-not $Domain) { $Domain = "dev." + ($HostIp -replace '\.', '-') + ".sslip.io" }
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$software = Resolve-Path (Join-Path $here "..\..")
$backend = Join-Path $software "backend"
$stage = Join-Path $env:TEMP "agri-deploy"
$target = "$User@$HostIp"
$sshOpts = @("-i", $Key, "-o", "StrictHostKeyChecking=accept-new", "-o", "ConnectTimeout=15")

Write-Host "[deploy] 主機 $target　網域 $Domain"
New-Item -ItemType Directory -Force $stage | Out-Null

Write-Host "[deploy] 1/4 建置後端 jar"
Push-Location $backend
& .\maven\apache-maven-3.9.6\bin\mvn.cmd -o -q package -DskipTests "-Dmaven-jar-plugin.version=3.4.2" 2>$null
$rc = $LASTEXITCODE; Pop-Location
if ($rc -ne 0) { throw "建置失敗（exit $rc）" }
Copy-Item (Join-Path $backend "target\agridispatch-backend-2.0.0.jar") (Join-Path $stage "app.jar") -Force

Write-Host "[deploy] 2/4 打包網頁（Software/web，正式版前端）與建表腳本（只取建表部分）"
tar -czf (Join-Path $stage "web.tar.gz") -C (Join-Path $software "web") .
Check "打包網頁"
$schema = Get-Content (Join-Path $software "database\01_agridispatch_oracle_schema.sql") -Encoding UTF8
$cut = ($schema | Select-String -SimpleMatch "【初始種子資料 (SEED DATA)】" | Select-Object -First 1).LineNumber
if (-not $cut) { throw "找不到建表腳本的種子資料標記" }
$ddl = $schema[0..($cut - 3)] -join "`n"
[IO.File]::WriteAllText((Join-Path $stage "schema-ddl.sql"), $ddl + "`n", (New-Object Text.UTF8Encoding $false))
foreach ($f in "server-setup.sh", "server-deploy.sh", "enable-https.sh") {
    $t = (Get-Content (Join-Path $here $f) -Raw -Encoding UTF8) -replace "`r`n", "`n"
    [IO.File]::WriteAllText((Join-Path $stage $f), $t, (New-Object Text.UTF8Encoding $false))
}
if ($Accounts) { Copy-Item $Accounts (Join-Path $stage "accounts.sql") -Force }
# 既有資料庫的 schema 變更（可重複執行），主機端依檔名順序執行
$migStage = Join-Path $stage "migrations"
if (Test-Path $migStage) { Remove-Item $migStage -Recurse -Force }
New-Item -ItemType Directory -Force $migStage | Out-Null
Get-ChildItem (Join-Path $software "database\migrations\*.sql") -ErrorAction SilentlyContinue | ForEach-Object {
    $t = (Get-Content $_.FullName -Raw -Encoding UTF8) -replace "`r`n", "`n"
    [IO.File]::WriteAllText((Join-Path $migStage $_.Name), $t, (New-Object Text.UTF8Encoding $false))
}
tar -czf (Join-Path $stage "migrations.tar.gz") -C $migStage .
Check "打包 migrations"

Write-Host "[deploy] 2b 編譯 LINE Bot（--release 17：主機是 Java 17）"
$bot = Join-Path $software "Line Bot"
$botStage = Join-Path $stage "linebot"
if (Test-Path $botStage) { Remove-Item $botStage -Recurse -Force }
New-Item -ItemType Directory -Force (Join-Path $botStage "bin") | Out-Null
$srcs = Get-ChildItem (Join-Path $bot "src\com\agridispatch\linebot\*.java") | ForEach-Object { $_.FullName }
& javac --release 17 -encoding UTF-8 -nowarn -d (Join-Path $botStage "bin") $srcs 2>$null
Check "編譯 LINE Bot"
foreach ($d in "i18n", "flex_templates", "rich_menu") { Copy-Item (Join-Path $bot $d) $botStage -Recurse -Force }
Get-ChildItem (Join-Path $botStage "rich_menu") -Filter "richmenu_ids.properties" -ErrorAction SilentlyContinue | Remove-Item -Force
tar -czf (Join-Path $stage "linebot.tar.gz") -C $botStage bin i18n flex_templates rich_menu
Check "打包 LINE Bot"

Write-Host "[deploy] 3/4 上傳"
& ssh @sshOpts $target "mkdir -p /tmp/agri-deploy"
Check "SSH 連線"
$files = @("app.jar", "web.tar.gz", "linebot.tar.gz", "migrations.tar.gz", "schema-ddl.sql", "server-setup.sh", "server-deploy.sh", "enable-https.sh")
if ($Accounts) { $files += "accounts.sql" }
& scp @sshOpts ($files | ForEach-Object { Join-Path $stage $_ }) "${target}:/tmp/agri-deploy/"
if ($LASTEXITCODE -ne 0) { throw "上傳失敗" }
if ($Accounts) { Remove-Item (Join-Path $stage "accounts.sql") -Force }

Write-Host "[deploy] 4/4 主機端"
if ($Setup) { & ssh @sshOpts $target "sudo bash /tmp/agri-deploy/server-setup.sh $Domain" ; if ($LASTEXITCODE -ne 0) { throw "server-setup.sh 失敗" } }
& ssh @sshOpts $target "sudo bash /tmp/agri-deploy/server-deploy.sh"
if ($LASTEXITCODE -ne 0) { throw "server-deploy.sh 失敗" }
Write-Host "[deploy] 完成：http://$Domain/web/（申請憑證後為 https）"

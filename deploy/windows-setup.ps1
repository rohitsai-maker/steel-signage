# Steel Signage - one-command setup for a Windows PC (Windows 10 / 11)
# Runs the server 24/7 on the PC, starts it automatically at boot, lets TVs on the
# same Wi-Fi connect, and (optionally) connects a free Cloudflare Tunnel so you can
# manage the screens from anywhere.
#
# HOW TO RUN: open "Windows PowerShell" as Administrator and paste:
#
#   $env:SS_PASSWORD='ChooseAStrongPassword'; irm https://raw.githubusercontent.com/rohitsai-maker/steel-signage/main/deploy/windows-setup.ps1 | iex
#
# To also connect Cloudflare Tunnel (remote access), add your tunnel token:
#
#   $env:SS_PASSWORD='ChooseAStrongPassword'; $env:SS_TUNNEL_TOKEN='eyJh...'; irm https://raw.githubusercontent.com/rohitsai-maker/steel-signage/main/deploy/windows-setup.ps1 | iex
#
# Re-running is safe: it updates the app and keeps your media, playlists and screens.

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Say($msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Fail($msg) { Write-Host "`nERROR: $msg" -ForegroundColor Red; throw $msg }

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) { Fail "Please open PowerShell with 'Run as administrator' and paste the command again." }

$Root     = 'C:\SteelSignage'
$AppDir   = Join-Path $Root 'app'
$DataDir  = Join-Path $Root 'data'
$Port     = if ($env:SS_PORT) { $env:SS_PORT } else { '8080' }
$ZipUrl   = 'https://github.com/rohitsai-maker/steel-signage/archive/refs/heads/main.zip'
$TaskName = 'Steel Signage Server'

New-Item -ItemType Directory -Force -Path $Root, $DataDir | Out-Null

# ---------------------------------------------------------------- password
$ConfFile = Join-Path $Root 'password.txt'
if ($env:SS_PASSWORD) {
  $Password = $env:SS_PASSWORD
} elseif (Test-Path $ConfFile) {
  $Password = (Get-Content $ConfFile -Raw).Trim()
} else {
  $Password = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 14 | ForEach-Object { [char]$_ })
}
Set-Content -Path $ConfFile -Value $Password -NoNewline
icacls $ConfFile /inheritance:r /grant:r "Administrators:F" "SYSTEM:F" | Out-Null

# ---------------------------------------------------------------- Node.js
Say 'Checking Node.js'
$node = Get-Command node -ErrorAction SilentlyContinue
if (-not $node) {
  if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    Fail "winget is missing. Install Node.js LTS from https://nodejs.org, then run this command again."
  }
  Say 'Installing Node.js LTS (takes a minute)'
  winget install --id OpenJS.NodeJS.LTS -e --silent --accept-package-agreements --accept-source-agreements | Out-Null
  $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User')
  $node = Get-Command node -ErrorAction SilentlyContinue
}
$NodeExe = if ($node) { $node.Source } elseif (Test-Path 'C:\Program Files\nodejs\node.exe') { 'C:\Program Files\nodejs\node.exe' } else { $null }
if (-not $NodeExe) { Fail "Node.js was not found after install. Restart the PC and run the command again." }
Write-Host "Node.js: $(& $NodeExe --version)"

# ---------------------------------------------------------------- download app
Say 'Downloading Steel Signage from GitHub'
# Stop a running copy (the start.cmd loop and its node child) before replacing files
Get-CimInstance Win32_Process -Filter "Name='cmd.exe'" | Where-Object { $_.CommandLine -like '*SteelSignage*start.cmd*' } | ForEach-Object { taskkill /PID $_.ProcessId /T /F 2>$null | Out-Null }
Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue | Stop-ScheduledTask -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process -Filter "Name='node.exe'" | Where-Object { $_.CommandLine -like '*server.js*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 1
$zip = Join-Path $env:TEMP 'steel-signage.zip'
$tmp = Join-Path $env:TEMP 'steel-signage-extract'
Invoke-WebRequest -Uri $ZipUrl -OutFile $zip -UseBasicParsing
if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
Expand-Archive -Path $zip -DestinationPath $tmp -Force
$src = Get-ChildItem $tmp -Directory | Select-Object -First 1
if (Test-Path $AppDir) { Remove-Item $AppDir -Recurse -Force }
Move-Item -Path $src.FullName -Destination $AppDir
Remove-Item $zip, $tmp -Recurse -Force -ErrorAction SilentlyContinue

# ---------------------------------------------------------------- start script (auto-restart loop)
Say 'Creating the background service'
$StartCmd = Join-Path $Root 'start.cmd'
@"
@echo off
set PORT=$Port
set ADMIN_USER=admin
set /p ADMIN_PASSWORD=<"$ConfFile"
set DATA_DIR=$DataDir
set MAX_UPLOAD_MB=2048
cd /d "$AppDir\server"
:loop
"$NodeExe" server.js >> "$Root\server.log" 2>&1
timeout /t 3 /nobreak >nul
goto loop
"@ | Set-Content -Path $StartCmd -Encoding ASCII

$action    = New-ScheduledTaskAction -Execute 'cmd.exe' -Argument "/c `"$StartCmd`""
$trigger   = New-ScheduledTaskTrigger -AtStartup
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
$settings  = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable `
             -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
Start-ScheduledTask -TaskName $TaskName

# ---------------------------------------------------------------- firewall + power
Say 'Allowing TVs on the local network to connect'
Get-NetFirewallRule -DisplayName 'Steel Signage' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -DisplayName 'Steel Signage' -Direction Inbound -Protocol TCP -LocalPort $Port -Action Allow -Profile Private,Domain,Public | Out-Null

Say 'Keeping the PC awake (no sleep / hibernate while plugged in)'
powercfg /change standby-timeout-ac 0 | Out-Null
powercfg /change hibernate-timeout-ac 0 | Out-Null
powercfg /hibernate off 2>$null | Out-Null

# ---------------------------------------------------------------- Cloudflare Tunnel (optional)
$tunnelMsg = 'Not set up (local network only). See the guide to enable remote access.'
if ($env:SS_TUNNEL_TOKEN) {
  Say 'Connecting Cloudflare Tunnel for remote access'
  $cf = Get-Command cloudflared -ErrorAction SilentlyContinue
  if (-not $cf) {
    winget install --id Cloudflare.cloudflared -e --silent --accept-package-agreements --accept-source-agreements | Out-Null
    $env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User')
    $cf = Get-Command cloudflared -ErrorAction SilentlyContinue
  }
  $CfExe = if ($cf) { $cf.Source } else { 'C:\Program Files (x86)\cloudflared\cloudflared.exe' }
  if (-not (Test-Path $CfExe)) { Fail "cloudflared could not be installed. Download it from https://github.com/cloudflare/cloudflared/releases and run again." }
  & $CfExe service uninstall 2>$null | Out-Null
  & $CfExe service install $env:SS_TUNNEL_TOKEN | Out-Null
  $tunnelMsg = 'Connected - use the public hostname you set in Cloudflare (e.g. https://signs.yourgym.in)'
}

# ---------------------------------------------------------------- check
Say 'Checking that the server is running'
$ok = $false
for ($i = 0; $i -lt 20; $i++) {
  Start-Sleep -Seconds 1
  try { Invoke-WebRequest -Uri "http://127.0.0.1:$Port/player" -UseBasicParsing -TimeoutSec 3 | Out-Null; $ok = $true; break } catch {}
}
if (-not $ok) { Fail "The server did not start. Check the log: $Root\server.log" }

$lan = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' -and $_.PrefixOrigin -ne 'WellKnown' } |
        Sort-Object -Property InterfaceMetric | Select-Object -First 1).IPAddress

Write-Host ""
Write-Host "=====================================================================" -ForegroundColor Green
Write-Host "  Steel Signage is running on this PC!" -ForegroundColor Green
Write-Host ""
Write-Host "  Admin panel (this PC)  : http://localhost:$Port"
Write-Host "  Address for the TVs    : http://${lan}:$Port"
Write-Host "  Username / password    : admin / $Password"
Write-Host "  Remote access          : $tunnelMsg"
Write-Host ""
Write-Host "  It starts automatically whenever this PC turns on."
Write-Host "  Media and settings are stored in $DataDir (back up this folder)."
Write-Host "  Tip: reserve this PC's IP in your Wi-Fi router so the TV address never changes."
Write-Host "=====================================================================" -ForegroundColor Green

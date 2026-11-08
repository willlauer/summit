# Build windows\dist\Summit.exe: one file, nothing else to install (WebView2 ships with Windows 10/11).
# Needs uv (winget install astral-sh.uv) and git. Run from anywhere: powershell -File windows\build.ps1
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

# The version, from git like the server and the phone app (shown in Settings › About)
$commits = git rev-list --count HEAD
$describe = git describe --always --dirty
"VERSION = `"0.1.$commits ($describe)`"" | Out-File -Encoding utf8 _version.py

uv sync --group build
uv run pyinstaller --noconfirm --clean --onefile --windowed --name Summit `
    --icon ..\src\summit\assets\icon.png summit_win.py
if ($LASTEXITCODE) { exit $LASTEXITCODE }
Write-Host "Built $PSScriptRoot\dist\Summit.exe"

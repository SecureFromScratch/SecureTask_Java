#Requires -Version 5.1
# Start the SecureTask BFF on Windows (no WSL required).
# Prerequisite: API must already be running (via Start-Api.ps1 or docker compose).

$ErrorActionPreference = "Stop"

Write-Host "Starting SecureTask BFF..." -ForegroundColor Cyan
& ".\gradlew.bat" :bff:bootRun
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

#Requires -Version 5.1
# Start the SecureTask API on Windows (no WSL required).
# Sets the LocalStack environment variables that bootRun needs, then launches Gradle.
# Prerequisite: docker compose up -d postgres localstack  (both services healthy)

$ErrorActionPreference = "Stop"

$env:AWS_REGION               = "us-east-1"
$env:AWS_ACCESS_KEY_ID        = "test"
$env:AWS_SECRET_ACCESS_KEY    = "test"
$env:SECRETS_MANAGER_ENDPOINT = "http://localhost:4566"
$env:S3_ENDPOINT              = "http://localhost:4566"
$env:DB_SECRET_NAME           = "securetask/db"
$env:JWT_SECRET_NAME          = "securetask/jwt"

Write-Host "Starting SecureTask API..." -ForegroundColor Cyan
& ".\gradlew.bat" :api:bootRun
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

#!/usr/bin/env bash
# Creates the S3 bucket used by SecureTask for file attachments.
# This script runs automatically when LocalStack starts (ready.d hook).
set -euo pipefail

echo "[LocalStack init] Creating S3 bucket: securetask-uploads"
awslocal s3 mb s3://securetask-uploads
echo "[LocalStack init] Bucket 'securetask-uploads' is ready."

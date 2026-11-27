@echo off & setlocal enabledelayedexpansion
rem Build the Docker image and push it to AWS ECR or Docker Hub.
rem Run from the repository root: scripts\build-push.bat

set "PROJECT_NAME=Resortlite_Mono2"
set "HELPER=%TEMP%\resortlite_sanitize.bat"
powershell -NoProfile -Command "Write-Output ('set IMAGE_NAME=' + ('!PROJECT_NAME!'.ToLower() -replace '[^a-z0-9]+','-').Trim('-'))" > "!HELPER!"
call "!HELPER!"
if "!IMAGE_NAME!"=="" set "IMAGE_NAME=resortlite-mono2"

where docker >nul 2>&1
if !ERRORLEVEL! neq 0 (
  echo Docker is not installed or not in PATH
  exit /b 1
)

echo === Build and push !IMAGE_NAME! ===
echo Select the registry
echo   1. AWS ECR
echo   2. Docker Hub
set /p REGISTRY_CHOICE="Enter 1 or 2: "

if "!REGISTRY_CHOICE!"=="1" (
  where aws >nul 2>&1
  if !ERRORLEVEL! neq 0 (
    echo The AWS CLI is not installed or not in PATH
    exit /b 1
  )
  set /p AWS_REGION="Enter the AWS region (for example us-east-1): "
  set /p AWS_ACCOUNT_ID="Enter the AWS account ID: "
  set /p ECR_REPO="Enter the ECR repository name or press Enter for !IMAGE_NAME!: "
  if "!ECR_REPO!"=="" set "ECR_REPO=!IMAGE_NAME!"
  set "REGISTRY_URL=!AWS_ACCOUNT_ID!.dkr.ecr.!AWS_REGION!.amazonaws.com"
  set "REPO_PATH=!ECR_REPO!"
) else if "!REGISTRY_CHOICE!"=="2" (
  set /p DOCKER_USERNAME="Enter the Docker Hub username: "
  set /p DOCKER_PASSWORD="Enter the Docker Hub password or access token: "
  set /p DOCKER_REPO="Enter the repository name or press Enter for !IMAGE_NAME!: "
  if "!DOCKER_REPO!"=="" set "DOCKER_REPO=!IMAGE_NAME!"
  set "REGISTRY_URL=docker.io"
  set "REPO_PATH=!DOCKER_USERNAME!/!DOCKER_REPO!"
) else (
  echo Invalid choice, enter 1 or 2
  exit /b 1
)

set /p IMAGE_TAG="Enter the image tag or press Enter for latest: "
if "!IMAGE_TAG!"=="" set "IMAGE_TAG=latest"
set "RAW_TAG=!IMAGE_TAG!"
powershell -NoProfile -Command "$t=('!RAW_TAG!' -replace '[^A-Za-z0-9_.-]+','-').Trim('-'); if ($t -eq '') { $t='latest' }; Write-Output ('set IMAGE_TAG=' + $t)" > "!HELPER!"
call "!HELPER!"
del "!HELPER!" >nul 2>&1

set "FULL_IMAGE_NAME=!REGISTRY_URL!/!REPO_PATH!:!IMAGE_TAG!"
echo Image to build !FULL_IMAGE_NAME!

echo Step 1/3 logging in to the registry...
if "!REGISTRY_CHOICE!"=="1" (
  aws ecr get-login-password --region !AWS_REGION! | docker login --username AWS --password-stdin !REGISTRY_URL!
  if !ERRORLEVEL! neq 0 (echo ECR login failed & exit /b 1)
  aws ecr describe-repositories --repository-names !ECR_REPO! --region !AWS_REGION! >nul 2>&1
  if !ERRORLEVEL! neq 0 (echo Creating ECR repository... & aws ecr create-repository --repository-name !ECR_REPO! --region !AWS_REGION!)
) else (
  echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
  if !ERRORLEVEL! neq 0 (echo Docker Hub login failed & exit /b 1)
)

echo Step 2/3 building the image...
docker build -f Dockerfile -t !FULL_IMAGE_NAME! .
if !ERRORLEVEL! neq 0 (echo Docker build failed & exit /b 1)

echo Step 3/3 pushing the image...
docker push !FULL_IMAGE_NAME!
if !ERRORLEVEL! neq 0 (echo Docker push failed & exit /b 1)

echo Done. Image pushed !FULL_IMAGE_NAME!
endlocal

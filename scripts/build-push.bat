@echo off & setlocal enabledelayedexpansion
rem Builds the ResortsLite image and pushes it to AWS ECR or Docker Hub.
rem Run from the repository root: scripts\build-push.bat

set "PROJECT_NAME=ResortsLite"
powershell -NoProfile -Command "$n=($env:PROJECT_NAME.ToLower() -replace '[^a-z0-9]+','-').Trim('-'); Set-Content -Path (Join-Path $env:TEMP 'resortslite_name.txt') -Value $n -NoNewline"
set /p IMAGE_NAME=<"%TEMP%\resortslite_name.txt"
del "%TEMP%\resortslite_name.txt" >nul 2>&1

echo === Build and push !IMAGE_NAME! ===

where docker >nul 2>&1
if !ERRORLEVEL! neq 0 (
  echo ERROR - docker is not installed or not in PATH
  exit /b 1
)

echo Select the registry
echo   1. AWS ECR (Amazon Elastic Container Registry)
echo   2. Docker Hub
set /p REGISTRY_CHOICE="Enter 1 or 2: "

if "!REGISTRY_CHOICE!"=="1" (
  where aws >nul 2>&1
  if !ERRORLEVEL! neq 0 (
    echo ERROR - the AWS CLI is not installed or not in PATH
    exit /b 1
  )
  set /p AWS_ACCOUNT_ID="Enter the AWS account ID: "
  set /p AWS_REGION="Enter the AWS region, for example us-east-1: "
  if "!AWS_ACCOUNT_ID!"=="" (
    echo ERROR - the AWS account ID is required
    exit /b 1
  )
  if "!AWS_REGION!"=="" (
    echo ERROR - the AWS region is required
    exit /b 1
  )
  set "REGISTRY_URL=!AWS_ACCOUNT_ID!.dkr.ecr.!AWS_REGION!.amazonaws.com"
  set "ECR_REPO=!IMAGE_NAME!"
  set "REPO_PATH=!REGISTRY_URL!/!ECR_REPO!"
) else if "!REGISTRY_CHOICE!"=="2" (
  set /p DOCKER_USERNAME="Enter the Docker Hub username: "
  set /p DOCKER_PASSWORD="Enter the Docker Hub password or access token: "
  if "!DOCKER_USERNAME!"=="" (
    echo ERROR - the Docker Hub username is required
    exit /b 1
  )
  if "!DOCKER_PASSWORD!"=="" (
    echo ERROR - the Docker Hub password is required
    exit /b 1
  )
  set "REPO_PATH=!DOCKER_USERNAME!/!IMAGE_NAME!"
) else (
  echo ERROR - invalid choice, enter 1 or 2
  exit /b 1
)

set "IMAGE_TAG="
set /p IMAGE_TAG="Enter IMAGE_TAG (press Enter for latest): "
if not "!IMAGE_TAG!"=="" (
  set "RAW_TAG=!IMAGE_TAG!"
  powershell -NoProfile -Command "$t=($env:RAW_TAG -replace '[^A-Za-z0-9_.-]+','-').Trim('-'); if ($t -eq '') { $t='latest' }; Set-Content -Path (Join-Path $env:TEMP 'resortslite_tag.txt') -Value $t -NoNewline"
  set /p IMAGE_TAG=<"%TEMP%\resortslite_tag.txt"
  del "%TEMP%\resortslite_tag.txt" >nul 2>&1
)
if "!IMAGE_TAG!"=="" set "IMAGE_TAG=latest"

set "FULL_IMAGE_NAME=!REPO_PATH!:!IMAGE_TAG!"

echo Building image !FULL_IMAGE_NAME! ...
docker build -f Dockerfile -t !FULL_IMAGE_NAME! .
if !ERRORLEVEL! neq 0 (
  echo Docker build failed
  exit /b 1
)
echo Build finished.

if "!REGISTRY_CHOICE!"=="1" (
  echo Logging in to AWS ECR ...
  aws ecr get-login-password --region !AWS_REGION! | docker login --username AWS --password-stdin !REGISTRY_URL!
  if !ERRORLEVEL! neq 0 (
    echo ECR login failed
    exit /b 1
  )
  aws ecr describe-repositories --repository-names !ECR_REPO! --region !AWS_REGION! >nul 2>&1
  if !ERRORLEVEL! neq 0 (
    echo Creating ECR repository...
    aws ecr create-repository --repository-name !ECR_REPO! --region !AWS_REGION!
    if !ERRORLEVEL! neq 0 (
      echo ECR repository creation failed
      exit /b 1
    )
  )
) else (
  echo Logging in to Docker Hub ...
  echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
  if !ERRORLEVEL! neq 0 (
    echo Docker Hub login failed
    exit /b 1
  )
)

echo Pushing !FULL_IMAGE_NAME! ...
docker push !FULL_IMAGE_NAME!
if !ERRORLEVEL! neq 0 (
  echo Docker push failed
  exit /b 1
)

echo Done. Image pushed !FULL_IMAGE_NAME!
echo Use this image URI with scripts\deploy-image.bat
endlocal

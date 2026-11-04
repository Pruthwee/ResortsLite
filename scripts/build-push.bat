@echo off
setlocal enabledelayedexpansion

set "PROJECT_NAME=ResortsLite"
for /f "usebackq delims=" %%i in (`powershell -Command "$p='!PROJECT_NAME!'; $p=$p.ToLower(); $p=$p -creplace '[^a-z0-9]','-'; $p=$p.Trim('-'); Write-Output $p"`) do set "IMAGE_NAME=%%i"

echo === Build and Push for !IMAGE_NAME! ===

echo Choose target platform:
echo 1. Amazon ECR (AWS)
echo 2. Docker Hub
set /p PLATFORM_CHOICE="Enter choice (1 or 2): "

if "!PLATFORM_CHOICE!"=="1" (
    set /p AWS_REGION="Enter AWS region: "
    set /p AWS_ACCOUNT_ID="Enter AWS account ID: "
    set /p ECR_REPO="Enter ECR repository name (or press Enter to use !IMAGE_NAME!): "
    if "!ECR_REPO!"=="" set "ECR_REPO=!IMAGE_NAME!"
    set "REGISTRY_URL=!AWS_ACCOUNT_ID!.dkr.ecr.!AWS_REGION!.amazonaws.com"
    set "FULL_IMAGE_NAME=!REGISTRY_URL!/!ECR_REPO!"

    set /p IMAGE_TAG="Enter image tag (or press Enter for latest): "
    if "!IMAGE_TAG!"=="" set "IMAGE_TAG=latest"

    echo Logging in to Amazon ECR...
    call aws ecr get-login-password --region !AWS_REGION! | docker login --username AWS --password-stdin !REGISTRY_URL!
    if !ERRORLEVEL! neq 0 (
        echo ECR login failed
        exit /b 1
    )

    call aws ecr describe-repositories --repository-names !ECR_REPO! --region !AWS_REGION! >nul 2>&1
    if !ERRORLEVEL! neq 0 (
        echo Creating ECR repository...
        call aws ecr create-repository --repository-name !ECR_REPO! --region !AWS_REGION!
    )

    echo Building Docker image...
    docker build -f Dockerfile -t !FULL_IMAGE_NAME!:!IMAGE_TAG! .
    if !ERRORLEVEL! neq 0 (
        echo Docker build failed
        exit /b 1
    )

    echo Pushing Docker image...
    docker push !FULL_IMAGE_NAME!:!IMAGE_TAG!
    if !ERRORLEVEL! neq 0 (
        echo Docker push failed
        exit /b 1
    )

    echo === Success ===
    echo Image pushed to: !FULL_IMAGE_NAME!:!IMAGE_TAG!
) else if "!PLATFORM_CHOICE!"=="2" (
    set /p DOCKER_USERNAME="Enter Docker Hub username: "
    call :getPassword
    set "DOCKER_PASSWORD=!INPUT_LINE!"

    set /p IMAGE_TAG="Enter image tag (or press Enter for latest): "
    if "!IMAGE_TAG!"=="" set "IMAGE_TAG=latest"
    set "FULL_IMAGE_NAME=!DOCKER_USERNAME!/!IMAGE_NAME!"

    echo Logging in to Docker Hub...
    echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
    if !ERRORLEVEL! neq 0 (
        echo Docker Hub login failed
        exit /b 1
    )

    echo Building Docker image...
    docker build -f Dockerfile -t !FULL_IMAGE_NAME!:!IMAGE_TAG! .
    if !ERRORLEVEL! neq 0 (
        echo Docker build failed
        exit /b 1
    )

    echo Pushing Docker image...
    docker push !FULL_IMAGE_NAME!:!IMAGE_TAG!
    if !ERRORLEVEL! neq 0 (
        echo Docker push failed
        exit /b 1
    )

    echo === Success ===
    echo Image pushed to: !FULL_IMAGE_NAME!:!IMAGE_TAG!
) else (
    echo Invalid choice. Exiting.
    exit /b 1
)
exit /b 0

:getPassword
set "INPUT_LINE="
powershell -Command "$p = Read-Host -AsSecureString; $b = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($p); $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b); Write-Output $plain" > "%TEMP%\docker_pw.txt"
set /p INPUT_LINE=<"%TEMP%\docker_pw.txt"
del "%TEMP%\docker_pw.txt"
exit /b 0

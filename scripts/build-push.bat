@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: build-push.bat — Build and push ResortsLite Docker image
:: ============================================================

set "PROJECT_NAME=resortsLite"
set "DOCKERFILE_PATH=Dockerfile"

:: Sanitise image name via PowerShell
for /f "delims=" %%i in ('powershell -NoProfile -Command "$n = 'resortsLite'.ToLower() -replace '[^a-z0-9]','-'; $n = $n.Trim('-'); Write-Output $n"') do set "IMAGE_NAME=%%i"

echo ============================================
echo   ResortsLite -- Docker Build ^& Push
echo ============================================
echo.

:: ---- Registry selection ----
echo Select container registry:
echo   1) AWS ECR
echo   2) Docker Hub
echo.
set /p "REGISTRY_CHOICE=Enter choice [1 or 2]: "

:: ---- Image tag ----
set /p "IMAGE_TAG_INPUT=Enter image tag (press Enter for 'latest'): "
if "!IMAGE_TAG_INPUT!"=="" (
    set "IMAGE_TAG=latest"
) else (
    for /f "delims=" %%t in ('powershell -NoProfile -Command "$t = '!IMAGE_TAG_INPUT!'.ToLower() -replace '[^a-z0-9._-]','-'; $t = $t.Trim('-'); if ($t -eq '') { 'latest' } else { $t }"') do set "IMAGE_TAG=%%t"
)

echo.
echo Image name : !IMAGE_NAME!
echo Image tag  : !IMAGE_TAG!
echo.

:: ============================================================
:: AWS ECR
:: ============================================================
if "!REGISTRY_CHOICE!"=="1" (
    set /p "AWS_REGION=Enter AWS region (e.g. us-east-1): "
    set /p "AWS_ACCOUNT_ID=Enter AWS account ID: "

    if "!AWS_REGION!"=="" (
        echo ERROR: AWS region is required.
        exit /b 1
    )
    if "!AWS_ACCOUNT_ID!"=="" (
        echo ERROR: AWS account ID is required.
        exit /b 1
    )

    set "REGISTRY_URL=!AWS_ACCOUNT_ID!.dkr.ecr.!AWS_REGION!.amazonaws.com"
    set "ECR_REPO=!IMAGE_NAME!"
    set "FULL_IMAGE_NAME=!REGISTRY_URL!/!ECR_REPO!:!IMAGE_TAG!"

    echo Logging in to Amazon ECR...
    aws ecr get-login-password --region !AWS_REGION! | docker login --username AWS --password-stdin !REGISTRY_URL!
    if !ERRORLEVEL! neq 0 (
        echo ERROR: ECR login failed.
        exit /b 1
    )

    :: Auto-create ECR repository if it does not exist
    aws ecr describe-repositories --repository-names !ECR_REPO! --region !AWS_REGION! >nul 2>&1
    if !ERRORLEVEL! neq 0 (
        echo Creating ECR repository !ECR_REPO!...
        aws ecr create-repository --repository-name !ECR_REPO! --region !AWS_REGION!
        if !ERRORLEVEL! neq 0 (
            echo ERROR: Failed to create ECR repository.
            exit /b 1
        )
    )

:: ============================================================
:: Docker Hub
:: ============================================================
) else if "!REGISTRY_CHOICE!"=="2" (
    set /p "DOCKER_USERNAME=Enter Docker Hub username: "
    set /p "DOCKER_PASSWORD=Enter Docker Hub password/token: "

    if "!DOCKER_USERNAME!"=="" (
        echo ERROR: Docker Hub username is required.
        exit /b 1
    )
    if "!DOCKER_PASSWORD!"=="" (
        echo ERROR: Docker Hub password is required.
        exit /b 1
    )

    set "FULL_IMAGE_NAME=!DOCKER_USERNAME!/!IMAGE_NAME!:!IMAGE_TAG!"

    echo Logging in to Docker Hub...
    echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Docker Hub login failed.
        exit /b 1
    )

) else (
    echo ERROR: Invalid registry choice. Please enter 1 or 2.
    exit /b 1
)

:: ============================================================
:: Build
:: ============================================================
echo.
echo Building Docker image: !FULL_IMAGE_NAME!
docker build -f "!DOCKERFILE_PATH!" -t "!FULL_IMAGE_NAME!" .
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker build failed.
    exit /b 1
)

echo.
echo Pushing image: !FULL_IMAGE_NAME!
docker push "!FULL_IMAGE_NAME!"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker push failed.
    exit /b 1
)

echo.
echo ============================================
echo   Build ^& push complete!
echo   Image: !FULL_IMAGE_NAME!
echo ============================================

endlocal

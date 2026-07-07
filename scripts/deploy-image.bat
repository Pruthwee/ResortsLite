@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: deploy-image.bat — Deploy ResortsLite to AWS EKS (Windows)
:: ============================================================

set "APP_NAME=resortsLite"
set "NAMESPACE=resortsLite"
set "MANIFESTS_DIR=kubernetes"

echo ============================================
echo   ResortsLite -- AWS EKS Deployment
echo ============================================
echo.

:: ---- AWS / EKS configuration ----
set /p "AWS_REGION=Enter AWS region (e.g. us-east-1): "
if "!AWS_REGION!"=="" (
    echo ERROR: AWS region is required.
    exit /b 1
)

set /p "CLUSTER_NAME=Enter EKS cluster name: "
if "!CLUSTER_NAME!"=="" (
    echo ERROR: EKS cluster name is required.
    exit /b 1
)

set /p "IMAGE_URI=Enter full Docker image URI: "
if "!IMAGE_URI!"=="" (
    echo ERROR: Docker image URI is required.
    exit /b 1
)

echo.
echo ---- Application environment variables ----
echo (Press Enter to keep the default value)
echo.

set /p "INPUT_REDIS_HOST=Enter REDIS_HOST (default: localhost): "
if "!INPUT_REDIS_HOST!"=="" set "INPUT_REDIS_HOST=localhost"

set /p "INPUT_REDIS_PORT=Enter REDIS_PORT (default: 6379): "
if "!INPUT_REDIS_PORT!"=="" set "INPUT_REDIS_PORT=6379"

set /p "INPUT_S3_BUCKET=Enter S3_BUCKET_NAME (default: resorts-reports-bucket): "
if "!INPUT_S3_BUCKET!"=="" set "INPUT_S3_BUCKET=resorts-reports-bucket"

set /p "INPUT_S3_PREFIX=Enter S3_BACKUP_PREFIX (default: backups/nightly/): "
if "!INPUT_S3_PREFIX!"=="" set "INPUT_S3_PREFIX=backups/nightly/"

set /p "INPUT_PAYMENT=Enter PAYMENT_API_ENDPOINT (default: http://payment-service/payments/charge): "
if "!INPUT_PAYMENT!"=="" set "INPUT_PAYMENT=http://payment-service/payments/charge"

:: ---- Configure kubectl ----
echo.
echo Configuring kubectl for EKS cluster: !CLUSTER_NAME! in !AWS_REGION! ...
aws eks update-kubeconfig --region !AWS_REGION! --name !CLUSTER_NAME!
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to configure kubectl.
    exit /b 1
)

echo Verifying cluster connectivity...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo ERROR: Cannot connect to EKS cluster.
    exit /b 1
)

:: ---- Substitute placeholders using PowerShell ----
echo.
echo Updating Kubernetes manifests with deployment values...

copy "!MANIFESTS_DIR!\deployment.yaml" "!MANIFESTS_DIR!\deployment.yaml.bak" >nul

powershell -NoProfile -Command ^
  "(Get-Content '!MANIFESTS_DIR!\deployment.yaml') ^
   -replace '{{IMAGE_URI}}','!IMAGE_URI!' ^
   -replace '{{REDIS_HOST}}','!INPUT_REDIS_HOST!' ^
   -replace '{{REDIS_PORT}}','!INPUT_REDIS_PORT!' ^
   -replace '{{S3_BUCKET_NAME}}','!INPUT_S3_BUCKET!' ^
   -replace '{{S3_BACKUP_PREFIX}}','!INPUT_S3_PREFIX!' ^
   -replace '{{PAYMENT_API_ENDPOINT}}','!INPUT_PAYMENT!' ^
   | Set-Content '!MANIFESTS_DIR!\deployment.yaml'"

if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to update deployment manifest.
    copy "!MANIFESTS_DIR!\deployment.yaml.bak" "!MANIFESTS_DIR!\deployment.yaml" >nul
    exit /b 1
)

:: ---- Apply manifests ----
echo.
echo Applying Kubernetes manifests...

echo   [1/4] Applying namespace...
kubectl apply -f "!MANIFESTS_DIR!\namespace.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply namespace. & exit /b 1 )

echo   [2/4] Applying deployment...
kubectl apply -f "!MANIFESTS_DIR!\deployment.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply deployment. & exit /b 1 )

echo   [3/4] Applying service...
kubectl apply -f "!MANIFESTS_DIR!\service.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply service. & exit /b 1 )

echo   [4/4] Applying ingress...
kubectl apply -f "!MANIFESTS_DIR!\ingress.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply ingress. & exit /b 1 )

:: ---- Restore original deployment.yaml ----
copy "!MANIFESTS_DIR!\deployment.yaml.bak" "!MANIFESTS_DIR!\deployment.yaml" >nul
del "!MANIFESTS_DIR!\deployment.yaml.bak" >nul

:: ---- Wait for rollout ----
echo.
echo Waiting for deployment rollout...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: Deployment rollout failed.
    echo Rollback command: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    exit /b 1
)

:: ---- Verify resources ----
echo.
echo Verifying deployed resources...
kubectl get pods,svc,ingress -n !NAMESPACE!

echo.
echo ============================================
echo   Deployment complete!
echo   Health check: http://<INGRESS_HOST>/actuator/health
echo ============================================
echo.
echo Rollback command (if needed):
echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!

endlocal

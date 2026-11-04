@echo off
setlocal enabledelayedexpansion

set "PROJECT_NAME=ResortsLite"
for /f "usebackq delims=" %%i in (`powershell -Command "$p='!PROJECT_NAME!'; $p=$p.ToLower(); $p=$p -creplace '[^a-z0-9]','-'; $p=$p.Trim('-'); Write-Output $p"`) do set "IMAGE_NAME=%%i"

echo === Deploy !IMAGE_NAME! to AWS EKS ===

set /p AWS_REGION="Enter AWS region: "
set /p CLUSTER_NAME="Enter EKS cluster name: "
set /p IMAGE_URI="Enter full image URI (registry/repo:tag): "

echo.
echo Enter values for environment variables (press Enter to skip):

set /p REDIS_HOST="Enter value for REDIS_HOST (or press Enter to skip): "
set /p REDIS_PORT="Enter value for REDIS_PORT (or press Enter to skip): "
call :getPassword
set "REDIS_PASSWORD=!INPUT_LINE!"

set /p PAYMENT_API_URL="Enter value for PAYMENT_API_URL (or press Enter to skip): "
set /p DB_USERNAME="Enter value for DB_USERNAME (or press Enter to skip): "
call :getPassword
set "DB_PASSWORD=!INPUT_LINE!"

echo.
echo Configuring kubectl for EKS cluster...
call aws eks update-kubeconfig --region !AWS_REGION! --name !CLUSTER_NAME!
if !ERRORLEVEL! neq 0 (
    echo Failed to configure kubectl
    exit /b 1
)
kubectl cluster-info
if !ERRORLEVEL! neq 0 exit /b 1

echo Applying namespace...
kubectl apply -f kubernetes\namespace.yaml
if !ERRORLEVEL! neq 0 (
    echo Failed to apply namespace
    exit /b 1
)

echo Creating Kubernetes Secret...
set "SECRET_ARGS="
if not "!REDIS_PASSWORD!"=="" set "SECRET_ARGS=!SECRET_ARGS! --from-literal=redis-password=\"!REDIS_PASSWORD!\""
if not "!DB_USERNAME!"=="" set "SECRET_ARGS=!SECRET_ARGS! --from-literal=db-username=\"!DB_USERNAME!\""
if not "!DB_PASSWORD!"=="" set "SECRET_ARGS=!SECRET_ARGS! --from-literal=db-password=\"!DB_PASSWORD!\""
if not "!REDIS_HOST!"=="" set "SECRET_ARGS=!SECRET_ARGS! --from-literal=redis-host=\"!REDIS_HOST!\""
if not "!REDIS_PORT!"=="" set "SECRET_ARGS=!SECRET_ARGS! --from-literal=redis-port=\"!REDIS_PORT!\""

if not "!SECRET_ARGS!"=="" (
    kubectl create secret generic !IMAGE_NAME!-secrets -n !IMAGE_NAME! !SECRET_ARGS! --dry-run=client -o yaml | kubectl apply -f -
)

echo Deploying application...

copy kubernetes\deployment.yaml kubernetes\deployment.yaml.bak >nul

powershell -Command "(gc kubernetes\deployment.yaml) -replace '{{IMAGE_URI}}', '!IMAGE_URI!' | Out-File -Encoding UTF8 kubernetes\deployment.yaml.tmp"
move /y kubernetes\deployment.yaml.tmp kubernetes\deployment.yaml >nul

powershell -Command "(gc kubernetes\deployment.yaml) -replace '{{NAMESPACE}}', '!IMAGE_NAME!' | Out-File -Encoding UTF8 kubernetes\deployment.yaml.tmp"
move /y kubernetes\deployment.yaml.tmp kubernetes\deployment.yaml >nul

if not "!REDIS_HOST!"=="" (
    powershell -Command "(gc kubernetes\deployment.yaml) -replace '{{REDIS_HOST}}', '!REDIS_HOST!' | Out-File -Encoding UTF8 kubernetes\deployment.yaml.tmp"
    move /y kubernetes\deployment.yaml.tmp kubernetes\deployment.yaml >nul
)
if not "!REDIS_PORT!"=="" (
    powershell -Command "(gc kubernetes\deployment.yaml) -replace '{{REDIS_PORT}}', '!REDIS_PORT!' | Out-File -Encoding UTF8 kubernetes\deployment.yaml.tmp"
    move /y kubernetes\deployment.yaml.tmp kubernetes\deployment.yaml >nul
)
if not "!REDIS_PASSWORD!"=="" (
    powershell -Command "(gc kubernetes\deployment.yaml) -replace '{{REDIS_PASSWORD}}', '!REDIS_PASSWORD!' | Out-File -Encoding UTF8 kubernetes\deployment.yaml.tmp"
    move /y kubernetes\deployment.yaml.tmp kubernetes\deployment.yaml >nul
)
if not "!PAYMENT_API_URL!"=="" (
    powershell -Command "(gc kubernetes\deployment.yaml) -replace '{{PAYMENT_API_URL}}', '!PAYMENT_API_URL!' | Out-File -Encoding UTF8 kubernetes\deployment.yaml.tmp"
    move /y kubernetes\deployment.yaml.tmp kubernetes\deployment.yaml >nul
)
if not "!DB_USERNAME!"=="" (
    powershell -Command "(gc kubernetes\deployment.yaml) -replace '{{DB_USERNAME}}', '!DB_USERNAME!' | Out-File -Encoding UTF8 kubernetes\deployment.yaml.tmp"
    move /y kubernetes\deployment.yaml.tmp kubernetes\deployment.yaml >nul
)
if not "!DB_PASSWORD!"=="" (
    powershell -Command "(gc kubernetes\deployment.yaml) -replace '{{DB_PASSWORD}}', '!DB_PASSWORD!' | Out-File -Encoding UTF8 kubernetes\deployment.yaml.tmp"
    move /y kubernetes\deployment.yaml.tmp kubernetes\deployment.yaml >nul
)

del kubernetes\deployment.yaml.bak

kubectl apply -f kubernetes\deployment.yaml
if !ERRORLEVEL! neq 0 (
    echo Failed to apply deployment
    exit /b 1
)
kubectl apply -f kubernetes\service.yaml
if !ERRORLEVEL! neq 0 (
    echo Failed to apply service
    exit /b 1
)
kubectl apply -f kubernetes\ingress.yaml
if !ERRORLEVEL! neq 0 (
    echo Failed to apply ingress
    exit /b 1
)

echo Waiting for deployment rollout...
kubectl rollout status deployment\!IMAGE_NAME! -n !IMAGE_NAME!
if !ERRORLEVEL! neq 0 (
    echo Deployment rollout failed
    exit /b 1
)

echo === Deployment Status ===
kubectl get pods,svc,ingress -n !IMAGE_NAME!

echo.
echo === Application URL ===
echo http://resortslite.example.com

echo.
echo === Deployment Complete ===
echo To rollback: kubectl rollout undo deployment/!IMAGE_NAME! -n !IMAGE_NAME!

exit /b 0

:getPassword
set "INPUT_LINE="
powershell -Command "$p = Read-Host -AsSecureString; $b = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($p); $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b); Write-Output $plain" > "%TEMP%\deploy_pw.txt"
set /p INPUT_LINE=<"%TEMP%\deploy_pw.txt"
del "%TEMP%\deploy_pw.txt"
exit /b 0

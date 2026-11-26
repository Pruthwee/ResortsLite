@echo off
setlocal enabledelayedexpansion

REM Deploy script for Resortslite_Mono on AWS EKS

echo === Resortslite EKS Deployment ===

set PROJECT_NAME=ResortsLite
set IMAGE_NAME=resortslite

REM Ask for AWS region
set /p AWS_REGION="Enter AWS region (e.g., us-east-1): "

REM Ask for EKS cluster name
set /p CLUSTER_NAME="Enter EKS cluster name: "

REM Ask for image URI
set /p IMAGE_URI="Enter full image URI (e.g., 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): "

REM Ask for environment variables
echo.
echo Enter values for environment variables (press Enter to skip):
set /p PAYMENT_API_ENDPOINT="Enter value for PAYMENT_API_ENDPOINT (or press Enter to skip): "

echo.
echo === Configuring kubectl for EKS cluster ===
aws eks update-kubeconfig --region !AWS_REGION! --name !CLUSTER_NAME!
if !ERRORLEVEL! neq 0 (
    echo Failed to configure kubectl
    exit /b 1
)

echo.
echo === Checking cluster connectivity ===
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo Cluster connectivity check failed
    exit /b 1
)

echo.
echo === Creating namespace ===
kubectl apply -f kubernetes\namespace.yaml
if !ERRORLEVEL! neq 0 (
    echo Namespace creation failed
    exit /b 1
)

echo.
echo === Creating secrets ===
set /p REDIS_HOST="Enter Redis host (or press Enter to skip): "
set /p REDIS_PORT="Enter Redis port (or press Enter to skip): "
set /p REDIS_PASSWORD="Enter Redis password (or press Enter to skip): "

set SECRET_ARGS=
if not "!REDIS_HOST!"=="" (
    set SECRET_ARGS=!SECRET_ARGS! --from-literal=redis-host=!REDIS_HOST!
)
if not "!REDIS_PORT!"=="" (
    set SECRET_ARGS=!SECRET_ARGS! --from-literal=redis-port=!REDIS_PORT!
)
if not "!REDIS_PASSWORD!"=="" (
    set SECRET_ARGS=!SECRET_ARGS! --from-literal=redis-password=!REDIS_PASSWORD!
)

if not "!SECRET_ARGS!"=="" (
    kubectl create secret generic resortslite-secrets -n resortslite !SECRET_ARGS! --dry-run=client -o yaml | kubectl apply -f -
    if !ERRORLEVEL! neq 0 (
        echo Secret creation failed
        exit /b 1
    )
)

echo.
echo === Replacing placeholders and deploying ===
REM Replace placeholders in deployment.yaml using PowerShell
powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{IMAGE_URI}}', '!IMAGE_URI!' | Set-Content kubernetes\deployment.yaml"

if not "!PAYMENT_API_ENDPOINT!"=="" (
    powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{PAYMENT_API_ENDPOINT}}', '!PAYMENT_API_ENDPOINT!' | Set-Content kubernetes\deployment.yaml"
) else (
    powershell -Command "(Get-Content kubernetes\deployment.yaml) -replace '{{PAYMENT_API_ENDPOINT}}', 'http://payment-svc:9090/payments/charge' | Set-Content kubernetes\deployment.yaml"
)

echo.
echo === Applying deployment ===
kubectl apply -f kubernetes\deployment.yaml
if !ERRORLEVEL! neq 0 (
    echo Deployment failed
    exit /b 1
)

echo.
echo === Applying service ===
kubectl apply -f kubernetes\service.yaml
if !ERRORLEVEL! neq 0 (
    echo Service creation failed
    exit /b 1
)

echo.
echo === Applying ingress ===
kubectl apply -f kubernetes\ingress.yaml
if !ERRORLEVEL! neq 0 (
    echo Ingress creation failed
    exit /b 1
)

echo.
echo === Waiting for deployment rollout ===
kubectl rollout status deployment/resortslite -n resortslite
if !ERRORLEVEL! neq 0 (
    echo Rollout failed
    exit /b 1
)

echo.
echo === Current pods and services ===
kubectl get pods,svc,ingress -n resortslite

echo.
echo === Deployment complete ===
echo Application URL: https://resortslite.example.com
echo.
echo === Rollback instructions ===
echo If the deployment fails, run: kubectl rollout undo deployment/resortslite -n resortslite
echo To check pod logs: kubectl logs -l app=resortslite -n resortslite --tail=100
echo To delete the deployment: kubectl delete -f kubernetes\ -n resortslite

endlocal

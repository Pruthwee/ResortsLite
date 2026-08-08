@echo off
setlocal enabledelayedexpansion

set "NAMESPACE=restorelitecontainer"
set "APP_NAME=restorelitecontainer"

set /p RESOURCE_GROUP="Enter Azure resource group: "
set /p CLUSTER_NAME="Enter AKS cluster name: "
set /p IMAGE_URI="Enter Docker image URI: "

if "%RESOURCE_GROUP%"=="" (
  echo Resource group is required
  exit /b 1
)
if "%CLUSTER_NAME%"=="" (
  echo Cluster name is required
  exit /b 1
)
if "%IMAGE_URI%"=="" (
  echo Image URI is required
  exit /b 1
)

set /p PAYMENT_API_URL="Enter value for PAYMENT_API_URL (or press Enter to skip): "
set /p INVENTORY_API_URL="Enter value for INVENTORY_API_URL (or press Enter to skip): "
set /p INVENTORY_SERVICE_PORT="Enter value for INVENTORY_SERVICE_PORT (or press Enter to skip): "
set /p REPORT_BASE_PATH="Enter value for REPORT_BASE_PATH (or press Enter to skip): "
set /p BACKUP_PATH="Enter value for BACKUP_PATH (or press Enter to skip): "

if "!PAYMENT_API_URL!"=="" set "PAYMENT_API_URL=http://payment-service:9090/payments/charge"
if "!INVENTORY_API_URL!"=="" set "INVENTORY_API_URL=https://inventory-service.internal:8081/rooms/available"
if "!INVENTORY_SERVICE_PORT!"=="" set "INVENTORY_SERVICE_PORT=8081"
if "!REPORT_BASE_PATH!"=="" set "REPORT_BASE_PATH=/tmp/reports"
if "!BACKUP_PATH!"=="" set "BACKUP_PATH=/tmp/backups"

az aks get-credentials --resource-group "%RESOURCE_GROUP%" --name "%CLUSTER_NAME%" --overwrite-existing
if %ERRORLEVEL% neq 0 (
  echo Failed to configure kubectl for AKS
  exit /b 1
)

kubectl cluster-info
if %ERRORLEVEL% neq 0 (
  echo Unable to connect to cluster
  exit /b 1
)

powershell -NoProfile -Command "(Get-Content 'kubernetes/deployment.yaml') -replace '\{\{IMAGE_URI\}\}','%IMAGE_URI%' -replace '\{\{PAYMENT_API_URL\}\}','!PAYMENT_API_URL!' -replace '\{\{INVENTORY_API_URL\}\}','!INVENTORY_API_URL!' -replace '\{\{INVENTORY_SERVICE_PORT\}\}','!INVENTORY_SERVICE_PORT!' -replace '\{\{REPORT_BASE_PATH\}\}','!REPORT_BASE_PATH!' -replace '\{\{BACKUP_PATH\}\}','!BACKUP_PATH!' | Set-Content 'kubernetes/deployment.rendered.yaml'"
if %ERRORLEVEL% neq 0 (
  echo Failed to render deployment manifest
  exit /b 1
)

kubectl apply -f kubernetes/namespace.yaml
if %ERRORLEVEL% neq 0 exit /b 1
kubectl apply -f kubernetes/deployment.rendered.yaml
if %ERRORLEVEL% neq 0 exit /b 1
kubectl apply -f kubernetes/service.yaml
if %ERRORLEVEL% neq 0 exit /b 1
kubectl apply -f kubernetes/ingress.yaml
if %ERRORLEVEL% neq 0 exit /b 1

kubectl rollout status deployment/%APP_NAME% -n %NAMESPACE%
if %ERRORLEVEL% neq 0 (
  echo Deployment rollout failed
  echo Rollback command: kubectl rollout undo deployment/%APP_NAME% -n %NAMESPACE%
  exit /b 1
)

kubectl get pods,svc,ingress -n %NAMESPACE%
echo Application should be available via host: restorelitecontainer.example.com
echo Rollback command: kubectl rollout undo deployment/%APP_NAME% -n %NAMESPACE%
endlocal

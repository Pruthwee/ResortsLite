@echo off
setlocal enabledelayedexpansion

set PROJECT_NAME=resortslite

echo -------------------------------------------------------
echo Deploying %PROJECT_NAME% to AWS EKS
echo -------------------------------------------------------

set /p AWS_REGION="Enter AWS Region [us-east-1]: "
if "!AWS_REGION!"=="" set AWS_REGION=us-east-1
set /p CLUSTER_NAME="Enter EKS Cluster Name: "
if "!CLUSTER_NAME!"=="" (
    echo Error: Cluster name is required.
    exit /b 1
)

set /p IMAGE_URI="Enter full Docker image URI (e.g., 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): "
if "!IMAGE_URI!"=="" (
    echo Error: Image URI is required.
    exit /b 1
)

echo Enter values for the following environment variables (or press Enter to skip):
set /p DB_URL="DB_URL [jdbc:h2:mem:resortdb;DB_CLOSE_DELAY=-1]: "
if "!DB_URL!"=="" set DB_URL=jdbc:h2:mem:resortdb;DB_CLOSE_DELAY=-1
set /p DB_USERNAME="DB_USERNAME [sa]: "
if "!DB_USERNAME!"=="" set DB_USERNAME=sa
set /p DB_PASSWORD="DB_PASSWORD: "
set /p PAYMENT_ENDPOINT="PAYMENT_ENDPOINT [http://payment-svc.internal:9090/charge]: "
if "!PAYMENT_ENDPOINT!"=="" set PAYMENT_ENDPOINT=http://payment-svc.internal:9090/charge
set /p INVENTORY_ENDPOINT="INVENTORY_ENDPOINT [http://inventory-svc.internal:8081/rooms]: "
if "!INVENTORY_ENDPOINT!"=="" set INVENTORY_ENDPOINT=http://inventory-svc.internal:8081/rooms
set /p NOTIFICATION_ENDPOINT="NOTIFICATION_ENDPOINT [http://notify.internal:7070/send]: "
if "!NOTIFICATION_ENDPOINT!"=="" set NOTIFICATION_ENDPOINT=http://notify.internal:7070/send

echo Updating manifests with provided values...
:: Using PowerShell for sed-like replacement in Windows
powershell -Command "(Get-Content kubernetes/deployment.yaml) -replace '\{\{IMAGE_URI\}\}', '!IMAGE_URI!' | Set-Content kubernetes/deployment.yaml"
powershell -Command "(Get-Content kubernetes/deployment.yaml) -replace '\{\{DB_URL\}\}', '!DB_URL!' | Set-Content kubernetes/deployment.yaml"
powershell -Command "(Get-Content kubernetes/deployment.yaml) -replace '\{\{DB_USERNAME\}\}', '!DB_USERNAME!' | Set-Content kubernetes/deployment.yaml"
powershell -Command "(Get-Content kubernetes/deployment.yaml) -replace '\{\{DB_PASSWORD\}\}', '!DB_PASSWORD!' | Set-Content kubernetes/deployment.yaml"
powershell -Command "(Get-Content kubernetes/deployment.yaml) -replace '\{\{PAYMENT_ENDPOINT\}\}', '!PAYMENT_ENDPOINT!' | Set-Content kubernetes/deployment.yaml"
powershell -Command "(Get-Content kubernetes/deployment.yaml) -replace '\{\{INVENTORY_ENDPOINT\}\}', '!INVENTORY_ENDPOINT!' | Set-Content kubernetes/deployment.yaml"
powershell -Command "(Get-Content kubernetes/deployment.yaml) -replace '\{\{NOTIFICATION_ENDPOINT\}\}', '!NOTIFICATION_ENDPOINT!' | Set-Content kubernetes/deployment.yaml"

echo Configuring kubectl for EKS cluster...
aws eks update-kubeconfig --region !AWS_REGION! --name !CLUSTER_NAME!

echo Verifying cluster connectivity...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo Error: Could not connect to EKS cluster
    exit /b 1
)

echo Applying Kubernetes manifests...
kubectl apply -f kubernetes/namespace.yaml
kubectl apply -f kubernetes/deployment.yaml
kubectl apply -f kubernetes/service.yaml
kubectl apply -f kubernetes/ingress.yaml

echo Waiting for deployment rollout...
kubectl rollout status deployment/%PROJECT_NAME% -n %PROJECT_NAME%

echo Verifying deployed resources...
kubectl get pods,svc,ingress -n %PROJECT_NAME%

echo -------------------------------------------------------
echo Deployment successful!
echo Application is accessible via the Ingress host defined in ingress.yaml
echo -------------------------------------------------------
echo Rollback instructions: kubectl rollout undo deployment/%PROJECT_NAME% -n %PROJECT_NAME%

@echo off & setlocal enabledelayedexpansion
rem Deploy the pushed image to AWS EKS. Run from the repository root: scripts\deploy-image.bat

set "APP_NAME=resortlite-mono2"
set "NAMESPACE=resortlite-mono2"

where aws >nul 2>&1
if !ERRORLEVEL! neq 0 (
  echo The AWS CLI is not installed or not in PATH
  exit /b 1
)
where kubectl >nul 2>&1
if !ERRORLEVEL! neq 0 (
  echo kubectl is not installed or not in PATH
  exit /b 1
)

echo === Deploying !APP_NAME! to AWS EKS ===
set /p AWS_REGION="Enter the AWS region (for example us-east-1): "
set /p CLUSTER_NAME="Enter the EKS cluster name: "
set /p IMAGE_URI="Enter the image URI (registry/repository and tag): "

if "!AWS_REGION!"=="" (echo Region is required & exit /b 1)
if "!CLUSTER_NAME!"=="" (echo Cluster name is required & exit /b 1)
if "!IMAGE_URI!"=="" (echo Image URI is required & exit /b 1)

set /p PAYMENT_API_URL="Enter value for PAYMENT_API_URL (or press Enter to skip): "
if "!PAYMENT_API_URL!"=="" set "PAYMENT_API_URL=http://payment-service:9090/payments/charge"
set /p REDIS_HOST="Enter value for REDIS_HOST, the ElastiCache endpoint (or press Enter to skip): "
set /p REDIS_PORT="Enter value for REDIS_PORT (or press Enter to skip): "
if "!REDIS_PORT!"=="" set "REDIS_PORT=6379"
set /p REDIS_SSL="Enter value for REDIS_SSL, true or false (or press Enter to skip): "
if "!REDIS_SSL!"=="" set "REDIS_SSL=false"
echo Note the next value is visible while you type it
set /p REDIS_PASSWORD="Enter value for REDIS_PASSWORD, the Redis AUTH token (or press Enter to skip): "

if "!REDIS_HOST!"=="" echo WARNING REDIS_HOST is empty, sessions and the booking cache need Redis

rem Keep the manifest template intact, the placeholders are replaced only while deploying
set "TEMPLATE_BACKUP=%TEMP%\resortlite-deployment-template.yaml"
copy /y kubernetes\deployment.yaml "!TEMPLATE_BACKUP!" >nul

echo Configuring kubectl for cluster !CLUSTER_NAME!...
aws eks update-kubeconfig --region !AWS_REGION! --name !CLUSTER_NAME!
if !ERRORLEVEL! neq 0 (
  echo Could not configure kubectl, check the region, the cluster name and your AWS credentials
  exit /b 1
)
kubectl cluster-info
if !ERRORLEVEL! neq 0 exit /b 1

echo Applying the namespace...
kubectl apply -f kubernetes\namespace.yaml
if !ERRORLEVEL! neq 0 (echo Namespace apply failed & exit /b 1)

echo Creating the Secret !APP_NAME!-secrets...
kubectl create secret generic !APP_NAME!-secrets -n !NAMESPACE! "--from-literal=REDIS_PASSWORD=!REDIS_PASSWORD!" --dry-run=client -o yaml | kubectl apply -f -
if !ERRORLEVEL! neq 0 (echo Secret creation failed & exit /b 1)

echo Filling in the placeholders...
powershell -NoProfile -Command "$f='kubernetes/deployment.yaml'; $c=Get-Content $f -Raw; $c=$c.Replace('{{IMAGE_URI}}','!IMAGE_URI!').Replace('{{NAMESPACE}}','!NAMESPACE!').Replace('{{PAYMENT_API_URL}}','!PAYMENT_API_URL!').Replace('{{REDIS_HOST}}','!REDIS_HOST!').Replace('{{REDIS_PORT}}','!REDIS_PORT!').Replace('{{REDIS_SSL}}','!REDIS_SSL!'); Set-Content -Path $f -Value $c -NoNewline"
if !ERRORLEVEL! neq 0 (
  echo Placeholder replacement failed
  copy /y "!TEMPLATE_BACKUP!" kubernetes\deployment.yaml >nul
  exit /b 1
)

echo Applying deployment, service and ingress...
kubectl apply -f kubernetes\deployment.yaml
set "DEPLOY_RESULT=!ERRORLEVEL!"
copy /y "!TEMPLATE_BACKUP!" kubernetes\deployment.yaml >nul
del "!TEMPLATE_BACKUP!" >nul 2>&1
if !DEPLOY_RESULT! neq 0 (echo Deployment apply failed & exit /b 1)
kubectl apply -f kubernetes\service.yaml
if !ERRORLEVEL! neq 0 (echo Service apply failed & exit /b 1)
kubectl apply -f kubernetes\ingress.yaml
if !ERRORLEVEL! neq 0 (echo Ingress apply failed & exit /b 1)

echo Waiting for the rollout...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
  echo The rollout did not finish
  echo Inspect with kubectl describe pods -n !NAMESPACE!
  echo Roll back with kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
  exit /b 1
)

kubectl get pods,svc,ingress -n !NAMESPACE!

set "HOST_FILE=%TEMP%\resortlite-ingress-host.txt"
kubectl get ingress !APP_NAME!-ingress -n !NAMESPACE! -o jsonpath="{.status.loadBalancer.ingress[0].hostname}" > "!HOST_FILE!" 2>nul
set "INGRESS_HOST="
set /p INGRESS_HOST=<"!HOST_FILE!"
del "!HOST_FILE!" >nul 2>&1
if not "!INGRESS_HOST!"=="" (
  echo Application URL is http://!INGRESS_HOST! with the Host header !APP_NAME!.example.com
) else (
  echo The load balancer address is not ready yet, check later with kubectl get ingress -n !NAMESPACE!
)

echo Rollback instructions if something goes wrong
echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
echo   kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE!
echo Done.
endlocal

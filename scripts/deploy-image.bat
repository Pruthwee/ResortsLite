@echo off & setlocal enabledelayedexpansion
rem Deploys the pushed ResortsLite image to an AWS EKS cluster with kubectl.
rem Run from the repository root: scripts\deploy-image.bat

set "APP_NAME=resortslite"
set "NAMESPACE=resortslite"

echo === Deploy !APP_NAME! to AWS EKS ===

where aws >nul 2>&1
if !ERRORLEVEL! neq 0 (
  echo ERROR - the AWS CLI is not installed or not in PATH
  exit /b 1
)
where kubectl >nul 2>&1
if !ERRORLEVEL! neq 0 (
  echo ERROR - kubectl is not installed or not in PATH
  exit /b 1
)

set /p AWS_REGION="Enter the AWS region, for example us-east-1: "
set /p CLUSTER_NAME="Enter the EKS cluster name: "
set /p IMAGE_URI="Enter the image URI, the full path with registry and tag: "

if "!AWS_REGION!"=="" (
  echo ERROR - the region is required
  exit /b 1
)
if "!CLUSTER_NAME!"=="" (
  echo ERROR - the cluster name is required
  exit /b 1
)
if "!IMAGE_URI!"=="" (
  echo ERROR - the image URI is required
  exit /b 1
)

set "REDIS_HOST="
set "REDIS_PORT="
set "REDIS_SSL="
set "PAYMENT_API_URL="
set "REDIS_PASSWORD="
set /p REDIS_HOST="Enter value for REDIS_HOST (or press Enter to skip): "
set /p REDIS_PORT="Enter value for REDIS_PORT (or press Enter to skip): "
set /p REDIS_SSL="Enter value for REDIS_SSL (or press Enter to skip): "
set /p PAYMENT_API_URL="Enter value for PAYMENT_API_URL (or press Enter to skip): "
echo The password is typed in clear text in this window, clear the screen afterwards.
set /p REDIS_PASSWORD="Enter value for REDIS_PASSWORD (or press Enter to skip): "

if "!REDIS_HOST!"=="" (
  echo ERROR - REDIS_HOST is required, the HTTP sessions and the booking cache are stored in Redis
  exit /b 1
)
if "!REDIS_PORT!"=="" set "REDIS_PORT=6379"
if "!REDIS_SSL!"=="" set "REDIS_SSL=false"

echo Configuring kubectl for the cluster !CLUSTER_NAME! ...
aws eks update-kubeconfig --region !AWS_REGION! --name !CLUSTER_NAME!
if !ERRORLEVEL! neq 0 (
  echo ERROR - could not configure kubectl for the cluster
  exit /b 1
)
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
  echo ERROR - the cluster is not reachable
  exit /b 1
)

rem Keep a copy of the manifest so the placeholders can be restored afterwards
copy /Y kubernetes\deployment.yaml kubernetes\deployment.yaml.orig >nul

echo Replacing the placeholders in kubernetes\deployment.yaml ...
powershell -NoProfile -Command "$f='kubernetes\deployment.yaml'; $c=[IO.File]::ReadAllText($f); $c=$c.Replace('{{IMAGE_URI}}',$env:IMAGE_URI).Replace('{{NAMESPACE}}',$env:NAMESPACE).Replace('{{REDIS_HOST}}',$env:REDIS_HOST).Replace('{{REDIS_PORT}}',$env:REDIS_PORT).Replace('{{REDIS_SSL}}',$env:REDIS_SSL).Replace('{{PAYMENT_API_URL}}',$env:PAYMENT_API_URL); [IO.File]::WriteAllText($f,$c)"
if !ERRORLEVEL! neq 0 (
  echo ERROR - the placeholders could not be replaced
  move /Y kubernetes\deployment.yaml.orig kubernetes\deployment.yaml >nul
  exit /b 1
)

echo Applying kubernetes\namespace.yaml ...
kubectl apply -f kubernetes\namespace.yaml
if !ERRORLEVEL! neq 0 (
  echo ERROR - namespace.yaml failed
  move /Y kubernetes\deployment.yaml.orig kubernetes\deployment.yaml >nul
  exit /b 1
)

echo Creating the Secret !APP_NAME!-secrets ...
kubectl create secret generic !APP_NAME!-secrets -n !NAMESPACE! --from-literal=REDIS_PASSWORD="!REDIS_PASSWORD!" --dry-run=client -o yaml | kubectl apply -f -
if !ERRORLEVEL! neq 0 (
  echo ERROR - the Secret could not be created
  move /Y kubernetes\deployment.yaml.orig kubernetes\deployment.yaml >nul
  exit /b 1
)

echo Applying kubernetes\deployment.yaml ...
kubectl apply -f kubernetes\deployment.yaml
set "DEPLOY_RESULT=!ERRORLEVEL!"
move /Y kubernetes\deployment.yaml.orig kubernetes\deployment.yaml >nul
if !DEPLOY_RESULT! neq 0 (
  echo ERROR - deployment.yaml failed
  exit /b 1
)

echo Applying kubernetes\service.yaml ...
kubectl apply -f kubernetes\service.yaml
if !ERRORLEVEL! neq 0 (
  echo ERROR - service.yaml failed
  exit /b 1
)

echo Applying kubernetes\ingress.yaml ...
kubectl apply -f kubernetes\ingress.yaml
if !ERRORLEVEL! neq 0 (
  echo ERROR - ingress.yaml failed
  exit /b 1
)

echo Waiting for the rollout ...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
  echo ERROR - the rollout failed
  echo Rollback with kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
  echo Inspect with kubectl describe pods -n !NAMESPACE! and kubectl logs deployment/!APP_NAME! -n !NAMESPACE!
  exit /b 1
)

kubectl get pods,svc,ingress -n !NAMESPACE!

kubectl get ingress !APP_NAME!-ingress -n !NAMESPACE! -o jsonpath="{.status.loadBalancer.ingress[0].hostname}" > "%TEMP%\resortslite_ingress.txt" 2>nul
set "INGRESS_HOST="
set /p INGRESS_HOST=<"%TEMP%\resortslite_ingress.txt"
del "%TEMP%\resortslite_ingress.txt" >nul 2>&1
if not "!INGRESS_HOST!"=="" (
  echo Application address is !INGRESS_HOST! over http
) else (
  echo The load balancer address is not ready yet. Check later with kubectl get ingress -n !NAMESPACE!
)
echo Health check path is /actuator/health
echo Deployment finished.
endlocal

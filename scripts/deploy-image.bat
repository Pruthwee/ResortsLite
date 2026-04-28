@echo off
setlocal enabledelayedexpansion

echo ==========================================
echo ResortsLite - Deploy to AWS ECS Fargate
echo ==========================================
echo.

REM Prompt for AWS region
set /p AWS_REGION="Enter AWS region (default: us-east-1): "
if "!AWS_REGION!"=="" set AWS_REGION=us-east-1
echo Using region: !AWS_REGION!
echo.

REM Get AWS Account ID
echo Retrieving AWS Account ID...
for /f "delims=" %%i in ('aws sts get-caller-identity --query Account --output text') do set ACCOUNT_ID=%%i
echo AWS Account ID: !ACCOUNT_ID!
echo.

REM Prompt for ECS cluster name
set /p CLUSTER_NAME="Enter ECS cluster name (default: resortslite-cluster): "
if "!CLUSTER_NAME!"=="" set CLUSTER_NAME=resortslite-cluster
echo Using cluster: !CLUSTER_NAME!
echo.

REM Check if cluster exists, create if not
echo Checking if ECS cluster exists...
aws ecs describe-clusters --clusters "!CLUSTER_NAME!" --region "!AWS_REGION!" --query "clusters[0].clusterName" --output text >nul 2>&1
if !ERRORLEVEL! neq 0 (
    echo Cluster does not exist. Creating ECS cluster...
    aws ecs create-cluster --cluster-name "!CLUSTER_NAME!" --region "!AWS_REGION!"
    echo ECS cluster created successfully
)
echo.

REM Prompt for VPC ID
set /p VPC_ID="Enter VPC ID: "
if "!VPC_ID!"=="" (
    echo Error: VPC ID is required
    exit /b 1
)
echo.

REM Prompt for subnet IDs
set /p SUBNETS_INPUT="Enter subnet IDs (comma-separated, at least 2): "
if "!SUBNETS_INPUT!"=="" (
    echo Error: At least 2 subnet IDs are required for high availability
    exit /b 1
)

REM Parse subnets
for /f "tokens=1,2 delims=," %%a in ("!SUBNETS_INPUT!") do (
    set SUBNET_1=%%a
    set SUBNET_2=%%b
)

REM Trim spaces
set SUBNET_1=!SUBNET_1: =!
set SUBNET_2=!SUBNET_2: =!

if "!SUBNET_1!"=="" (
    echo Error: At least 2 subnet IDs are required
    exit /b 1
)
if "!SUBNET_2!"=="" (
    echo Error: At least 2 subnet IDs are required
    exit /b 1
)

echo Using subnets: !SUBNET_1!, !SUBNET_2!
echo.

REM Prompt for security group ID
set /p SECURITY_GROUP="Enter security group ID (must allow inbound traffic on port 8080): "
if "!SECURITY_GROUP!"=="" (
    echo Error: Security group ID is required
    exit /b 1
)
echo.

REM Prompt for ECR image URI
set /p IMAGE_URI="Enter ECR image URI (e.g., 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): "
if "!IMAGE_URI!"=="" (
    echo Error: Image URI is required
    exit /b 1
)
echo Using image: !IMAGE_URI!
echo.

REM Ask if load balancer is needed
set /p NEED_LB="Do you need a load balancer for this service? (y/n): "

if /i "!NEED_LB!"=="y" (
    echo.
    echo === Creating Application Load Balancer ===
    
    REM Create ALB
    set ALB_NAME=resortslite-alb
    echo Creating Application Load Balancer: !ALB_NAME!
    
    for /f "delims=" %%i in ('aws elbv2 create-load-balancer --name "!ALB_NAME!" --subnets "!SUBNET_1!" "!SUBNET_2!" --security-groups "!SECURITY_GROUP!" --scheme internet-facing --type application --ip-address-type ipv4 --region "!AWS_REGION!" --query "LoadBalancers[0].LoadBalancerArn" --output text') do set ALB_ARN=%%i
    
    echo ALB created: !ALB_ARN!
    
    REM Get ALB DNS name
    for /f "delims=" %%i in ('aws elbv2 describe-load-balancers --load-balancer-arns "!ALB_ARN!" --region "!AWS_REGION!" --query "LoadBalancers[0].DNSName" --output text') do set ALB_DNS=%%i
    
    echo ALB DNS: !ALB_DNS!
    echo.
    
    REM Create Target Group with target-type ip
    set TG_NAME=resortslite-tg
    echo Creating Target Group: !TG_NAME!
    
    for /f "delims=" %%i in ('aws elbv2 create-target-group --name "!TG_NAME!" --protocol HTTP --port 8080 --vpc-id "!VPC_ID!" --target-type ip --health-check-enabled --health-check-protocol HTTP --health-check-path "/actuator/health" --health-check-interval-seconds 30 --health-check-timeout-seconds 5 --healthy-threshold-count 2 --unhealthy-threshold-count 3 --region "!AWS_REGION!" --query "TargetGroups[0].TargetGroupArn" --output text') do set TARGET_GROUP_ARN=%%i
    
    echo Target Group created: !TARGET_GROUP_ARN!
    echo.
    
    REM Create Listener
    echo Creating ALB Listener...
    aws elbv2 create-listener --load-balancer-arn "!ALB_ARN!" --protocol HTTP --port 80 --default-actions Type=forward,TargetGroupArn="!TARGET_GROUP_ARN!" --region "!AWS_REGION!" >nul
    
    echo Listener created successfully
    echo.
    
    REM Copy service definition
    copy ecs\service-definition.json %TEMP%\service-definition-temp.json >nul
    set TEMP_SERVICE_DEF=%TEMP%\service-definition-temp.json
    
) else (
    echo.
    echo Skipping load balancer creation
    echo.
    
    REM Remove loadBalancers section from service definition
    powershell -Command "(Get-Content ecs\service-definition.json | ConvertFrom-Json | Select-Object * -ExcludeProperty loadBalancers,healthCheckGracePeriodSeconds | ConvertTo-Json -Depth 10) | Set-Content %TEMP%\service-definition-temp.json"
    set TEMP_SERVICE_DEF=%TEMP%\service-definition-temp.json
)

REM Create CloudWatch log group
set LOG_GROUP=/ecs/resortslite
echo Creating CloudWatch log group: !LOG_GROUP!
aws logs create-log-group --log-group-name "!LOG_GROUP!" --region "!AWS_REGION!" 2>nul
if !ERRORLEVEL! neq 0 (
    echo Log group already exists
)
echo.

REM Prepare task definition with environment variables
echo === Environment Configuration ===
echo Using existing application.properties configuration values
echo.

REM Default environment values from application.properties
set DB_URL=jdbc:h2:mem:resortdb;DB_CLOSE_DELAY=-1
set DB_USERNAME=sa
set DB_PASSWORD=
set REDIS_HOST=localhost
set REDIS_PORT=6379
set REDIS_PASSWORD=
set S3_BUCKET_NAME=resorts-reports
set PAYMENT_ENDPOINT=http://payment-svc.internal:9090/charge
set INVENTORY_ENDPOINT=http://inventory-svc.internal:8081/rooms
set NOTIFICATION_ENDPOINT=http://notify.internal:7070/send

REM Replace placeholders in task definition
set TEMP_TASK_DEF=%TEMP%\task-definition-temp.json
powershell -Command "(Get-Content ecs\task-definition.json) -replace '{{IMAGE_URI}}', '!IMAGE_URI!' -replace '{{AWS_REGION}}', '!AWS_REGION!' -replace '{{ACCOUNT_ID}}', '!ACCOUNT_ID!' -replace '{{DB_URL}}', '!DB_URL!' -replace '{{DB_USERNAME}}', '!DB_USERNAME!' -replace '{{DB_PASSWORD}}', '!DB_PASSWORD!' -replace '{{REDIS_HOST}}', '!REDIS_HOST!' -replace '{{REDIS_PORT}}', '!REDIS_PORT!' -replace '{{REDIS_PASSWORD}}', '!REDIS_PASSWORD!' -replace '{{S3_BUCKET_NAME}}', '!S3_BUCKET_NAME!' -replace '{{PAYMENT_ENDPOINT}}', '!PAYMENT_ENDPOINT!' -replace '{{INVENTORY_ENDPOINT}}', '!INVENTORY_ENDPOINT!' -replace '{{NOTIFICATION_ENDPOINT}}', '!NOTIFICATION_ENDPOINT!' | Set-Content !TEMP_TASK_DEF!"

REM Register task definition
echo ==========================================
echo Registering ECS task definition...
echo ==========================================
for /f "delims=" %%i in ('aws ecs register-task-definition --cli-input-json file://!TEMP_TASK_DEF! --region "!AWS_REGION!" --query "taskDefinition.taskDefinitionArn" --output text') do set TASK_DEF_ARN=%%i

echo Task definition registered: !TASK_DEF_ARN!
echo.

REM Replace placeholders in service definition
powershell -Command "(Get-Content !TEMP_SERVICE_DEF!) -replace '{{CLUSTER_NAME}}', '!CLUSTER_NAME!' -replace '{{SUBNET_1}}', '!SUBNET_1!' -replace '{{SUBNET_2}}', '!SUBNET_2!' -replace '{{SECURITY_GROUP}}', '!SECURITY_GROUP!' -replace '{{TARGET_GROUP_ARN}}', '!TARGET_GROUP_ARN!' | Set-Content !TEMP_SERVICE_DEF!"

REM Check if service exists
set SERVICE_NAME=resortslite-service
echo Checking if ECS service exists...
for /f "delims=" %%i in ('aws ecs describe-services --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!" --query "services[?status==`ACTIVE`].serviceName" --output text 2^>nul') do set EXISTING_SERVICE=%%i

if "!EXISTING_SERVICE!"=="" (
    echo Service does not exist. Creating new ECS service...
    aws ecs create-service --cli-input-json file://!TEMP_SERVICE_DEF! --region "!AWS_REGION!" >nul
    echo ECS service created successfully
) else (
    echo Service exists. Updating ECS service...
    aws ecs update-service --cluster "!CLUSTER_NAME!" --service "!SERVICE_NAME!" --task-definition "!TASK_DEF_ARN!" --region "!AWS_REGION!" >nul
    echo ECS service updated successfully
)
echo.

REM Wait for service to become stable
echo ==========================================
echo Waiting for service to become stable...
echo ==========================================
aws ecs wait services-stable --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!"

echo Service is stable
echo.

REM Verify deployment
echo ==========================================
echo Verifying deployment...
echo ==========================================
aws ecs describe-services --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!" --query "services[0].[serviceName,status,runningCount,desiredCount]" --output table

echo.
echo ==========================================
echo DEPLOYMENT SUCCESSFUL!
echo ==========================================
echo Service: !SERVICE_NAME!
echo Cluster: !CLUSTER_NAME!
echo Region: !AWS_REGION!
echo Task Definition: !TASK_DEF_ARN!

if /i "!NEED_LB!"=="y" (
    echo Load Balancer DNS: !ALB_DNS!
    echo Application URL: http://!ALB_DNS!
)

echo CloudWatch Logs: !LOG_GROUP!
echo.
echo To view logs:
echo   aws logs tail !LOG_GROUP! --follow --region !AWS_REGION!
echo.
echo To check service status:
echo   aws ecs describe-services --cluster !CLUSTER_NAME! --services !SERVICE_NAME! --region !AWS_REGION!
echo ==========================================

REM Cleanup temp files
del /q "!TEMP_TASK_DEF!" 2>nul
del /q "!TEMP_SERVICE_DEF!" 2>nul

endlocal

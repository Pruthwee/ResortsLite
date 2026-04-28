#!/bin/bash

# Deploy ResortsLite to AWS ECS Fargate
# This script deploys the Docker image to AWS ECS Fargate

set -e
set -o pipefail

echo "=========================================="
echo "ResortsLite - Deploy to AWS ECS Fargate"
echo "=========================================="
echo ""

# Prompt for AWS region
read -p "Enter AWS region (default: us-east-1): " AWS_REGION
AWS_REGION=${AWS_REGION:-us-east-1}
echo "Using region: $AWS_REGION"
echo ""

# Get AWS Account ID
echo "Retrieving AWS Account ID..."
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
echo "AWS Account ID: $ACCOUNT_ID"
echo ""

# Prompt for ECS cluster name
read -p "Enter ECS cluster name (default: resortslite-cluster): " CLUSTER_NAME
CLUSTER_NAME=${CLUSTER_NAME:-resortslite-cluster}
echo "Using cluster: $CLUSTER_NAME"
echo ""

# Check if cluster exists, create if not
echo "Checking if ECS cluster exists..."
aws ecs describe-clusters --clusters "$CLUSTER_NAME" --region "$AWS_REGION" --query "clusters[0].clusterName" --output text 2>/dev/null | grep -q "$CLUSTER_NAME" || {
    echo "Cluster does not exist. Creating ECS cluster..."
    aws ecs create-cluster --cluster-name "$CLUSTER_NAME" --region "$AWS_REGION"
    echo "ECS cluster created successfully"
}
echo ""

# Prompt for VPC ID
read -p "Enter VPC ID: " VPC_ID
if [ -z "$VPC_ID" ]; then
    echo "Error: VPC ID is required"
    exit 1
fi
echo ""

# Prompt for subnet IDs
read -p "Enter subnet IDs (comma-separated, at least 2): " SUBNETS_INPUT
if [ -z "$SUBNETS_INPUT" ]; then
    echo "Error: At least 2 subnet IDs are required for high availability"
    exit 1
fi

# Convert comma-separated subnets to array
IFS=',' read -ra SUBNETS_ARRAY <<< "$SUBNETS_INPUT"
SUBNET_1=$(echo "${SUBNETS_ARRAY[0]}" | xargs)
SUBNET_2=$(echo "${SUBNETS_ARRAY[1]}" | xargs)

if [ -z "$SUBNET_1" ] || [ -z "$SUBNET_2" ]; then
    echo "Error: At least 2 subnet IDs are required"
    exit 1
fi

echo "Using subnets: $SUBNET_1, $SUBNET_2"
echo ""

# Prompt for security group ID
read -p "Enter security group ID (must allow inbound traffic on port 8080): " SECURITY_GROUP
if [ -z "$SECURITY_GROUP" ]; then
    echo "Error: Security group ID is required"
    exit 1
fi
echo ""

# Prompt for ECR image URI
read -p "Enter ECR image URI (e.g., 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
    echo "Error: Image URI is required"
    exit 1
fi
echo "Using image: $IMAGE_URI"
echo ""

# Ask if load balancer is needed
read -p "Do you need a load balancer for this service? (y/n): " NEED_LB
NEED_LB=$(echo "$NEED_LB" | tr '[:upper:]' '[:lower:]')

if [ "$NEED_LB" == "y" ] || [ "$NEED_LB" == "yes" ]; then
    echo ""
    echo "=== Creating Application Load Balancer ==="
    
    # Create ALB
    ALB_NAME="resortslite-alb"
    echo "Creating Application Load Balancer: $ALB_NAME"
    
    ALB_ARN=$(aws elbv2 create-load-balancer \
        --name "$ALB_NAME" \
        --subnets "$SUBNET_1" "$SUBNET_2" \
        --security-groups "$SECURITY_GROUP" \
        --scheme internet-facing \
        --type application \
        --ip-address-type ipv4 \
        --region "$AWS_REGION" \
        --query 'LoadBalancers[0].LoadBalancerArn' \
        --output text)
    
    echo "ALB created: $ALB_ARN"
    
    # Get ALB DNS name
    ALB_DNS=$(aws elbv2 describe-load-balancers \
        --load-balancer-arns "$ALB_ARN" \
        --region "$AWS_REGION" \
        --query 'LoadBalancers[0].DNSName' \
        --output text)
    
    echo "ALB DNS: $ALB_DNS"
    echo ""
    
    # Create Target Group with target-type ip (required for Fargate)
    TG_NAME="resortslite-tg"
    echo "Creating Target Group: $TG_NAME"
    
    TARGET_GROUP_ARN=$(aws elbv2 create-target-group \
        --name "$TG_NAME" \
        --protocol HTTP \
        --port 8080 \
        --vpc-id "$VPC_ID" \
        --target-type ip \
        --health-check-enabled \
        --health-check-protocol HTTP \
        --health-check-path "/actuator/health" \
        --health-check-interval-seconds 30 \
        --health-check-timeout-seconds 5 \
        --healthy-threshold-count 2 \
        --unhealthy-threshold-count 3 \
        --region "$AWS_REGION" \
        --query 'TargetGroups[0].TargetGroupArn' \
        --output text)
    
    echo "Target Group created: $TARGET_GROUP_ARN"
    echo ""
    
    # Create Listener
    echo "Creating ALB Listener..."
    aws elbv2 create-listener \
        --load-balancer-arn "$ALB_ARN" \
        --protocol HTTP \
        --port 80 \
        --default-actions Type=forward,TargetGroupArn="$TARGET_GROUP_ARN" \
        --region "$AWS_REGION" >/dev/null
    
    echo "Listener created successfully"
    echo ""
    
    # Update service definition with load balancer
    TEMP_SERVICE_DEF="/tmp/service-definition-temp.json"
    cp ecs/service-definition.json "$TEMP_SERVICE_DEF"
    
else
    echo ""
    echo "Skipping load balancer creation"
    echo ""
    
    # Remove loadBalancers section from service definition
    TEMP_SERVICE_DEF="/tmp/service-definition-temp.json"
    jq 'del(.loadBalancers) | del(.healthCheckGracePeriodSeconds)' ecs/service-definition.json > "$TEMP_SERVICE_DEF"
fi

# Create CloudWatch log group
LOG_GROUP="/ecs/resortslite"
echo "Creating CloudWatch log group: $LOG_GROUP"
aws logs create-log-group --log-group-name "$LOG_GROUP" --region "$AWS_REGION" 2>/dev/null || echo "Log group already exists"
echo ""

# Prepare task definition with environment variables
echo "=== Environment Configuration ==="
echo "Using existing application.properties configuration values"
echo ""

# Default environment values from application.properties
DB_URL="jdbc:h2:mem:resortdb;DB_CLOSE_DELAY=-1"
DB_USERNAME="sa"
DB_PASSWORD=""
REDIS_HOST="localhost"
REDIS_PORT="6379"
REDIS_PASSWORD=""
S3_BUCKET_NAME="resorts-reports"
PAYMENT_ENDPOINT="http://payment-svc.internal:9090/charge"
INVENTORY_ENDPOINT="http://inventory-svc.internal:8081/rooms"
NOTIFICATION_ENDPOINT="http://notify.internal:7070/send"

# Replace placeholders in task definition
TEMP_TASK_DEF="/tmp/task-definition-temp.json"
sed "s|{{IMAGE_URI}}|$IMAGE_URI|g; \
     s|{{AWS_REGION}}|$AWS_REGION|g; \
     s|{{ACCOUNT_ID}}|$ACCOUNT_ID|g; \
     s|{{DB_URL}}|$DB_URL|g; \
     s|{{DB_USERNAME}}|$DB_USERNAME|g; \
     s|{{DB_PASSWORD}}|$DB_PASSWORD|g; \
     s|{{REDIS_HOST}}|$REDIS_HOST|g; \
     s|{{REDIS_PORT}}|$REDIS_PORT|g; \
     s|{{REDIS_PASSWORD}}|$REDIS_PASSWORD|g; \
     s|{{S3_BUCKET_NAME}}|$S3_BUCKET_NAME|g; \
     s|{{PAYMENT_ENDPOINT}}|$PAYMENT_ENDPOINT|g; \
     s|{{INVENTORY_ENDPOINT}}|$INVENTORY_ENDPOINT|g; \
     s|{{NOTIFICATION_ENDPOINT}}|$NOTIFICATION_ENDPOINT|g" \
     ecs/task-definition.json > "$TEMP_TASK_DEF"

# Register task definition
echo "=========================================="
echo "Registering ECS task definition..."
echo "=========================================="
TASK_DEF_ARN=$(aws ecs register-task-definition \
    --cli-input-json file://"$TEMP_TASK_DEF" \
    --region "$AWS_REGION" \
    --query 'taskDefinition.taskDefinitionArn' \
    --output text)

echo "Task definition registered: $TASK_DEF_ARN"
echo ""

# Replace placeholders in service definition
sed -i "s|{{CLUSTER_NAME}}|$CLUSTER_NAME|g; \
        s|{{SUBNET_1}}|$SUBNET_1|g; \
        s|{{SUBNET_2}}|$SUBNET_2|g; \
        s|{{SECURITY_GROUP}}|$SECURITY_GROUP|g" \
        "$TEMP_SERVICE_DEF"

if [ "$NEED_LB" == "y" ] || [ "$NEED_LB" == "yes" ]; then
    sed -i "s|{{TARGET_GROUP_ARN}}|$TARGET_GROUP_ARN|g" "$TEMP_SERVICE_DEF"
fi

# Check if service exists
SERVICE_NAME="resortslite-service"
echo "Checking if ECS service exists..."
EXISTING_SERVICE=$(aws ecs describe-services \
    --cluster "$CLUSTER_NAME" \
    --services "$SERVICE_NAME" \
    --region "$AWS_REGION" \
    --query 'services[?status==`ACTIVE`].serviceName' \
    --output text 2>/dev/null)

if [ -z "$EXISTING_SERVICE" ] || [ "$EXISTING_SERVICE" == "None" ]; then
    echo "Service does not exist. Creating new ECS service..."
    aws ecs create-service \
        --cli-input-json file://"$TEMP_SERVICE_DEF" \
        --region "$AWS_REGION" >/dev/null
    echo "ECS service created successfully"
else
    echo "Service exists. Updating ECS service..."
    aws ecs update-service \
        --cluster "$CLUSTER_NAME" \
        --service "$SERVICE_NAME" \
        --task-definition "$TASK_DEF_ARN" \
        --region "$AWS_REGION" >/dev/null
    echo "ECS service updated successfully"
fi
echo ""

# Wait for service to become stable
echo "=========================================="
echo "Waiting for service to become stable..."
echo "=========================================="
aws ecs wait services-stable \
    --cluster "$CLUSTER_NAME" \
    --services "$SERVICE_NAME" \
    --region "$AWS_REGION"

echo "Service is stable"
echo ""

# Verify deployment
echo "=========================================="
echo "Verifying deployment..."
echo "=========================================="
aws ecs describe-services \
    --cluster "$CLUSTER_NAME" \
    --services "$SERVICE_NAME" \
    --region "$AWS_REGION" \
    --query 'services[0].[serviceName,status,runningCount,desiredCount]' \
    --output table

echo ""
echo "=========================================="
echo "DEPLOYMENT SUCCESSFUL!"
echo "=========================================="
echo "Service: $SERVICE_NAME"
echo "Cluster: $CLUSTER_NAME"
echo "Region: $AWS_REGION"
echo "Task Definition: $TASK_DEF_ARN"

if [ "$NEED_LB" == "y" ] || [ "$NEED_LB" == "yes" ]; then
    echo "Load Balancer DNS: $ALB_DNS"
    echo "Application URL: http://$ALB_DNS"
fi

echo "CloudWatch Logs: $LOG_GROUP"
echo ""
echo "To view logs:"
echo "  aws logs tail $LOG_GROUP --follow --region $AWS_REGION"
echo ""
echo "To check service status:"
echo "  aws ecs describe-services --cluster $CLUSTER_NAME --services $SERVICE_NAME --region $AWS_REGION"
echo "=========================================="

# Cleanup temp files
rm -f "$TEMP_TASK_DEF" "$TEMP_SERVICE_DEF"

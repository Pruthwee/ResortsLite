# ResortsLite - AWS ECS Fargate Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Local Development Setup](#local-development-setup)
4. [Building and Pushing Docker Images](#building-and-pushing-docker-images)
5. [AWS ECS Fargate Prerequisites](#aws-ecs-fargate-prerequisites)
6. [ECS Fargate Setup](#ecs-fargate-setup)
7. [ECS Task Definition Explained](#ecs-task-definition-explained)
8. [ECS Service Configuration](#ecs-service-configuration)
9. [ECS Fargate Deployment Walkthrough](#ecs-fargate-deployment-walkthrough)
10. [Configuration Management](#configuration-management)
11. [Monitoring and Logging](#monitoring-and-logging)
12. [Troubleshooting](#troubleshooting)
13. [Scaling and Management](#scaling-and-management)
14. [Security Considerations](#security-considerations)
15. [Technology-Specific Notes](#technology-specific-notes)

---

## Overview

ResortsLite is a Spring Boot 2.7.18 application built with Java 8, designed for containerized deployment on AWS ECS Fargate. This guide provides comprehensive instructions for building, deploying, and managing the application in a production environment.

**Application Details:**
- **Framework:** Spring Boot 2.7.18
- **Java Version:** 8
- **Build Tool:** Maven 3.9.4
- **Application Port:** 8080
- **Health Check Endpoint:** /actuator/health
- **Package Type:** JAR

**Key Features:**
- Spring Boot Actuator for health monitoring
- Redis session management for distributed sessions
- AWS S3 integration for file storage
- H2 in-memory database (configurable for production databases)
- External service integrations (payment, inventory, notification)

---

## Prerequisites

### Required Software
- **Docker:** Version 20.10 or higher
- **Docker Compose:** Version 2.0 or higher
- **AWS CLI:** Version 2.x
- **Java Development Kit (JDK):** Version 8 or higher (for local development)
- **Maven:** Version 3.6 or higher (for local builds)
- **Git:** For version control

### AWS Account Requirements
- Active AWS account with appropriate permissions
- IAM user with programmatic access (Access Key ID and Secret Access Key)
- Permissions for:
  - ECS (Elastic Container Service)
  - ECR (Elastic Container Registry)
  - VPC (Virtual Private Cloud)
  - EC2 (for security groups and networking)
  - CloudWatch Logs
  - IAM (for role creation)
  - Elastic Load Balancing (optional, for ALB/NLB)

### System Requirements
- **Memory:** Minimum 4GB RAM for local development
- **Disk Space:** At least 10GB free space
- **Operating System:** Linux, macOS, or Windows 10/11 with WSL2

---

## Local Development Setup

### 1. Clone the Repository
```bash
git clone <repository-url>
cd comp
```

### 2. Configure Application Properties
Edit `src/main/resources/application.properties` to configure local settings:

```properties
# Local development configuration
server.port=8080

# H2 Database (in-memory for local development)
spring.datasource.url=jdbc:h2:mem:resortdb;DB_CLOSE_DELAY=-1
spring.datasource.username=sa
spring.datasource.password=

# Redis (use local Redis or Docker container)
spring.redis.host=localhost
spring.redis.port=6379

# AWS S3 (use LocalStack or actual AWS credentials)
aws.s3.bucket-name=resorts-reports-local
aws.s3.region=us-east-1
```

### 3. Build the Application Locally
```bash
# Using Maven
mvn clean package -DskipTests

# Run the application
java -jar target/resortsLite-1.0.0.jar
```

### 4. Access the Application
- **Application URL:** http://localhost:8080
- **Health Check:** http://localhost:8080/actuator/health
- **H2 Console:** http://localhost:8080/h2-console

### 5. Run with Docker Compose (Local Testing)
```bash
# Build and start the application
docker-compose up --build

# Stop the application
docker-compose down
```

---

## Building and Pushing Docker Images

### Using build-push.sh (Linux/macOS)

```bash
# Make the script executable
chmod +x scripts/build-push.sh

# Run the script
./scripts/build-push.sh
```

**Script Workflow:**
1. Prompts for image tag (default: latest)
2. Asks to select registry type (AWS ECR or Docker Hub)
3. Collects registry credentials and details
4. Builds the Docker image using multi-stage Dockerfile
5. Authenticates with the selected registry
6. Pushes the image to the registry

**Example - AWS ECR:**
```
Enter image tag (default: latest): v1.0.0
Select container registry:
1. AWS ECR (Elastic Container Registry)
2. Docker Hub
Enter choice (1 or 2): 1

Enter AWS region (default: us-east-1): us-east-1
Enter AWS account ID: 123456789012
Enter ECR repository name (default: resortslite): resortslite

Full image name: 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:v1.0.0
```

### Using build-push.bat (Windows)

```cmd
# Run the script
scripts\build-push.bat
```

The Windows script follows the same workflow as the Linux version.

### Manual Build and Push

```bash
# Build the image
docker build -t resortslite:latest .

# Tag for ECR
docker tag resortslite:latest 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest

# Login to ECR
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin 123456789012.dkr.ecr.us-east-1.amazonaws.com

# Push to ECR
docker push 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest
```

---

## AWS ECS Fargate Prerequisites

### 1. VPC Configuration
Ensure you have a VPC with:
- At least 2 subnets in different Availability Zones (for high availability)
- Internet Gateway attached (for public access)
- Route tables configured for internet access

**Create VPC (if needed):**
```bash
aws ec2 create-vpc --cidr-block 10.0.0.0/16 --region us-east-1
```

### 2. Security Group Configuration
Create a security group that allows:
- Inbound traffic on port 8080 (application port)
- Inbound traffic on port 80 (if using ALB)
- Outbound traffic to all destinations (for external service calls)

**Create Security Group:**
```bash
aws ec2 create-security-group \
  --group-name resortslite-sg \
  --description "Security group for ResortsLite ECS tasks" \
  --vpc-id vpc-xxxxxxxxx \
  --region us-east-1

# Add inbound rule for port 8080
aws ec2 authorize-security-group-ingress \
  --group-id sg-xxxxxxxxx \
  --protocol tcp \
  --port 8080 \
  --cidr 0.0.0.0/0 \
  --region us-east-1
```

### 3. IAM Roles

#### ECS Task Execution Role
This role allows ECS to pull images from ECR and write logs to CloudWatch.

**Create Role:**
```bash
aws iam create-role \
  --role-name ecsTaskExecutionRole \
  --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": {"Service": "ecs-tasks.amazonaws.com"},
      "Action": "sts:AssumeRole"
    }]
  }'

# Attach managed policy
aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy
```

#### ECS Task Role (Optional)
This role grants permissions to the application running in the container (e.g., S3 access, DynamoDB access).

**Create Role:**
```bash
aws iam create-role \
  --role-name ecsTaskRole \
  --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": {"Service": "ecs-tasks.amazonaws.com"},
      "Action": "sts:AssumeRole"
    }]
  }'

# Attach policies for S3 access
aws iam attach-role-policy \
  --role-name ecsTaskRole \
  --policy-arn arn:aws:iam::aws:policy/AmazonS3FullAccess
```

### 4. CloudWatch Log Group
Create a log group for application logs:

```bash
aws logs create-log-group \
  --log-group-name /ecs/resortslite \
  --region us-east-1
```

---

## ECS Fargate Setup

### 1. Create ECS Cluster
```bash
aws ecs create-cluster \
  --cluster-name resortslite-cluster \
  --region us-east-1
```

### 2. Create ECR Repository
```bash
aws ecr create-repository \
  --repository-name resortslite \
  --region us-east-1
```

### 3. Build and Push Docker Image
Follow the instructions in [Building and Pushing Docker Images](#building-and-pushing-docker-images).

---

## ECS Task Definition Explained

The task definition (`ecs/task-definition.json`) defines how your container should run on ECS Fargate.

### Key Components

#### 1. Launch Type Configuration
```json
{
  "requiresCompatibilities": ["FARGATE"],
  "networkMode": "awsvpc"
}
```
- **FARGATE:** Serverless compute engine for containers
- **awsvpc:** Each task gets its own elastic network interface (ENI)

#### 2. CPU and Memory
```json
{
  "cpu": "512",
  "memory": "1024"
}
```
**Valid Fargate CPU/Memory Combinations:**
- CPU: "256" (.25 vCPU) → Memory: 512, 1024, 2048 MB
- CPU: "512" (.5 vCPU) → Memory: 1024, 2048, 3072, 4096 MB
- CPU: "1024" (1 vCPU) → Memory: 2048-8192 MB (increments of 1024)
- CPU: "2048" (2 vCPU) → Memory: 4096-16384 MB (increments of 1024)
- CPU: "4096" (4 vCPU) → Memory: 8192-30720 MB (increments of 1024)

#### 3. Execution Role
```json
{
  "executionRoleArn": "arn:aws:iam::123456789012:role/ecsTaskExecutionRole"
}
```
Allows ECS to:
- Pull images from ECR
- Write logs to CloudWatch
- Retrieve secrets from Secrets Manager (if configured)

#### 4. Task Role
```json
{
  "taskRoleArn": "arn:aws:iam::123456789012:role/ecsTaskRole"
}
```
Grants permissions to the application:
- Access to S3 buckets
- Access to DynamoDB tables
- Access to other AWS services

#### 5. Container Definition
```json
{
  "containerDefinitions": [{
    "name": "resortslite",
    "image": "123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest",
    "essential": true,
    "portMappings": [{
      "containerPort": 8080,
      "protocol": "tcp"
    }],
    "environment": [
      {"name": "SERVER_PORT", "value": "8080"},
      {"name": "SPRING_PROFILES_ACTIVE", "value": "docker"}
    ],
    "logConfiguration": {
      "logDriver": "awslogs",
      "options": {
        "awslogs-group": "/ecs/resortslite",
        "awslogs-region": "us-east-1",
        "awslogs-stream-prefix": "ecs"
      }
    }
  }]
}
```

#### 6. Environment Variables
Configure application settings via environment variables:
- **SERVER_PORT:** Application port (8080)
- **SPRING_PROFILES_ACTIVE:** Spring profile (docker, production)
- **DB_URL:** Database connection string
- **REDIS_HOST:** Redis server hostname
- **S3_BUCKET_NAME:** S3 bucket for file storage
- **AWS_REGION:** AWS region for services

---

## ECS Service Configuration

The service definition (`ecs/service-definition.json`) manages the deployment and scaling of tasks.

### Key Components

#### 1. Service Configuration
```json
{
  "serviceName": "resortslite-service",
  "cluster": "resortslite-cluster",
  "taskDefinition": "resortslite-task",
  "desiredCount": 2,
  "launchType": "FARGATE"
}
```
- **desiredCount:** Number of task instances to run (2 for high availability)
- **launchType:** FARGATE for serverless deployment

#### 2. Network Configuration
```json
{
  "networkConfiguration": {
    "awsvpcConfiguration": {
      "subnets": ["subnet-xxxxxxxx", "subnet-yyyyyyyy"],
      "securityGroups": ["sg-xxxxxxxxx"],
      "assignPublicIp": "ENABLED"
    }
  }
}
```
- **subnets:** At least 2 subnets in different AZs
- **securityGroups:** Security group allowing inbound traffic on port 8080
- **assignPublicIp:** ENABLED for internet access (use DISABLED for private subnets with NAT)

#### 3. Deployment Configuration
```json
{
  "deploymentConfiguration": {
    "maximumPercent": 200,
    "minimumHealthyPercent": 50,
    "deploymentCircuitBreaker": {
      "enable": true,
      "rollback": true
    }
  }
}
```
- **maximumPercent:** Maximum percentage of tasks during deployment (200% = rolling deployment)
- **minimumHealthyPercent:** Minimum healthy tasks during deployment (50%)
- **deploymentCircuitBreaker:** Automatic rollback on deployment failure

#### 4. Load Balancer Configuration (Optional)
```json
{
  "loadBalancers": [{
    "targetGroupArn": "arn:aws:elasticloadbalancing:...",
    "containerName": "resortslite",
    "containerPort": 8080
  }],
  "healthCheckGracePeriodSeconds": 300
}
```
- **targetGroupArn:** ARN of the target group (created by deploy script)
- **healthCheckGracePeriodSeconds:** Time to wait before health checks start (300s for JVM startup)

#### 5. Tags
```json
{
  "tags": [
    {"key": "Environment", "value": "production"},
    {"key": "Application", "value": "ResortsLite"}
  ]
}
```
**CRITICAL:** Always use "tags" parameter, NEVER "serviceTags" (invalid and causes deployment failure).

---

## ECS Fargate Deployment Walkthrough

### Using deploy-image.sh (Linux/macOS)

```bash
# Make the script executable
chmod +x scripts/deploy-image.sh

# Run the script
./scripts/deploy-image.sh
```

**Deployment Steps:**

1. **Enter AWS Region:**
   ```
   Enter AWS region (default: us-east-1): us-east-1
   ```

2. **ECS Cluster:**
   ```
   Enter ECS cluster name (default: resortslite-cluster): resortslite-cluster
   ```
   - Script checks if cluster exists, creates if not

3. **Network Configuration:**
   ```
   Enter VPC ID: vpc-xxxxxxxxx
   Enter subnet IDs (comma-separated, at least 2): subnet-xxxxxxxx,subnet-yyyyyyyy
   Enter security group ID: sg-xxxxxxxxx
   ```

4. **Docker Image:**
   ```
   Enter ECR image URI: 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest
   ```

5. **Load Balancer:**
   ```
   Do you need a load balancer for this service? (y/n): y
   ```
   - If yes: Script automatically creates ALB and Target Group
   - If no: Service runs without load balancer (direct task access)

6. **Deployment:**
   - Script registers task definition
   - Creates or updates ECS service
   - Waits for service to become stable
   - Displays deployment status and URLs

### Using deploy-image.bat (Windows)

```cmd
# Run the script
scripts\deploy-image.bat
```

The Windows script follows the same workflow as the Linux version.

### Manual Deployment

#### 1. Register Task Definition
```bash
aws ecs register-task-definition \
  --cli-input-json file://ecs/task-definition.json \
  --region us-east-1
```

#### 2. Create Service
```bash
aws ecs create-service \
  --cli-input-json file://ecs/service-definition.json \
  --region us-east-1
```

#### 3. Update Service (for redeployment)
```bash
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --task-definition resortslite-task:2 \
  --force-new-deployment \
  --region us-east-1
```

---

## Configuration Management

### Environment Variables

#### Application Configuration
- **SERVER_PORT:** Application port (default: 8080)
- **SPRING_PROFILES_ACTIVE:** Spring profile (docker, production)
- **JAVA_OPTS:** JVM options (-Xmx512m -Xms256m)

#### Database Configuration
- **DB_URL:** JDBC connection string
- **DB_USERNAME:** Database username
- **DB_PASSWORD:** Database password

#### Redis Configuration
- **REDIS_HOST:** Redis server hostname
- **REDIS_PORT:** Redis server port (default: 6379)
- **REDIS_PASSWORD:** Redis password (if required)

#### AWS S3 Configuration
- **S3_BUCKET_NAME:** S3 bucket name for file storage
- **AWS_REGION:** AWS region for S3 and other services

#### External Services
- **PAYMENT_ENDPOINT:** Payment service URL
- **INVENTORY_ENDPOINT:** Inventory service URL
- **NOTIFICATION_ENDPOINT:** Notification service URL

### Using AWS Secrets Manager (Recommended for Production)

Store sensitive data in AWS Secrets Manager:

```bash
# Create secret
aws secretsmanager create-secret \
  --name resortslite/db-password \
  --secret-string "your-secure-password" \
  --region us-east-1
```

Update task definition to use secrets:
```json
{
  "secrets": [
    {
      "name": "DB_PASSWORD",
      "valueFrom": "arn:aws:secretsmanager:us-east-1:123456789012:secret:resortslite/db-password"
    }
  ]
}
```

---

## Monitoring and Logging

### CloudWatch Logs

**View Logs:**
```bash
# Tail logs in real-time
aws logs tail /ecs/resortslite --follow --region us-east-1

# View logs for specific time range
aws logs filter-log-events \
  --log-group-name /ecs/resortslite \
  --start-time 1609459200000 \
  --end-time 1609545600000 \
  --region us-east-1
```

**Log Insights Queries:**
```sql
# Find errors
fields @timestamp, @message
| filter @message like /ERROR/
| sort @timestamp desc
| limit 100

# Application startup time
fields @timestamp, @message
| filter @message like /Started ResortsLiteApplication/
| sort @timestamp desc
```

### CloudWatch Metrics

**ECS Service Metrics:**
- CPUUtilization
- MemoryUtilization
- RunningTaskCount
- DesiredTaskCount

**View Metrics:**
```bash
aws cloudwatch get-metric-statistics \
  --namespace AWS/ECS \
  --metric-name CPUUtilization \
  --dimensions Name=ServiceName,Value=resortslite-service Name=ClusterName,Value=resortslite-cluster \
  --start-time 2024-01-01T00:00:00Z \
  --end-time 2024-01-01T23:59:59Z \
  --period 300 \
  --statistics Average \
  --region us-east-1
```

### Application Health Checks

**Health Check Endpoint:**
```bash
# Check application health
curl http://<alb-dns>/actuator/health

# Expected response
{
  "status": "UP",
  "components": {
    "diskSpace": {"status": "UP"},
    "ping": {"status": "UP"},
    "redis": {"status": "UP"}
  }
}
```

### CloudWatch Alarms

**Create CPU Alarm:**
```bash
aws cloudwatch put-metric-alarm \
  --alarm-name resortslite-high-cpu \
  --alarm-description "Alert when CPU exceeds 80%" \
  --metric-name CPUUtilization \
  --namespace AWS/ECS \
  --statistic Average \
  --period 300 \
  --threshold 80 \
  --comparison-operator GreaterThanThreshold \
  --evaluation-periods 2 \
  --dimensions Name=ServiceName,Value=resortslite-service Name=ClusterName,Value=resortslite-cluster \
  --region us-east-1
```

---

## Troubleshooting

### Common Issues

#### 1. Task Fails to Start

**Symptoms:**
- Tasks transition from PENDING to STOPPED
- No logs in CloudWatch

**Possible Causes:**
- Invalid CPU/memory combination
- Image pull failure (ECR permissions)
- Invalid task definition

**Solutions:**
```bash
# Check task stopped reason
aws ecs describe-tasks \
  --cluster resortslite-cluster \
  --tasks <task-id> \
  --region us-east-1 \
  --query 'tasks[0].stoppedReason'

# Verify ECR permissions
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin <account-id>.dkr.ecr.us-east-1.amazonaws.com

# Check task execution role
aws iam get-role --role-name ecsTaskExecutionRole
```

#### 2. Network Connectivity Issues

**Symptoms:**
- Cannot access application via ALB
- Tasks cannot connect to external services

**Solutions:**
```bash
# Verify security group rules
aws ec2 describe-security-groups \
  --group-ids sg-xxxxxxxxx \
  --region us-east-1

# Check subnet route tables
aws ec2 describe-route-tables \
  --filters "Name=association.subnet-id,Values=subnet-xxxxxxxx" \
  --region us-east-1

# Verify internet gateway attachment
aws ec2 describe-internet-gateways \
  --filters "Name=attachment.vpc-id,Values=vpc-xxxxxxxxx" \
  --region us-east-1
```

#### 3. Application Crashes

**Symptoms:**
- Tasks restart frequently
- Out of memory errors in logs

**Solutions:**
```bash
# Increase memory allocation in task definition
# Edit ecs/task-definition.json
{
  "cpu": "1024",
  "memory": "2048"
}

# Check JVM heap settings
# Adjust JAVA_OPTS environment variable
JAVA_OPTS="-Xmx1024m -Xms512m -XX:+UseContainerSupport"

# View application logs
aws logs tail /ecs/resortslite --follow --region us-east-1
```

#### 4. Health Check Failures

**Symptoms:**
- Tasks marked as unhealthy
- ALB target group shows unhealthy targets

**Solutions:**
```bash
# Verify health check endpoint
curl http://<task-ip>:8080/actuator/health

# Check health check configuration in target group
aws elbv2 describe-target-health \
  --target-group-arn <target-group-arn> \
  --region us-east-1

# Increase health check grace period
# Edit ecs/service-definition.json
{
  "healthCheckGracePeriodSeconds": 600
}
```

#### 5. Deployment Failures

**Symptoms:**
- Service update fails
- Circuit breaker triggers rollback

**Solutions:**
```bash
# Check service events
aws ecs describe-services \
  --cluster resortslite-cluster \
  --services resortslite-service \
  --region us-east-1 \
  --query 'services[0].events[0:10]'

# Force new deployment
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --force-new-deployment \
  --region us-east-1

# Rollback to previous task definition
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --task-definition resortslite-task:1 \
  --region us-east-1
```

### Debugging Commands

```bash
# List running tasks
aws ecs list-tasks \
  --cluster resortslite-cluster \
  --service-name resortslite-service \
  --region us-east-1

# Describe task details
aws ecs describe-tasks \
  --cluster resortslite-cluster \
  --tasks <task-id> \
  --region us-east-1

# Get task network interface
aws ecs describe-tasks \
  --cluster resortslite-cluster \
  --tasks <task-id> \
  --region us-east-1 \
  --query 'tasks[0].attachments[0].details[?name==`networkInterfaceId`].value' \
  --output text

# SSH into task (using ECS Exec)
aws ecs execute-command \
  --cluster resortslite-cluster \
  --task <task-id> \
  --container resortslite \
  --interactive \
  --command "/bin/sh" \
  --region us-east-1
```

---

## Scaling and Management

### Service Auto Scaling

#### 1. Register Scalable Target
```bash
aws application-autoscaling register-scalable-target \
  --service-namespace ecs \
  --resource-id service/resortslite-cluster/resortslite-service \
  --scalable-dimension ecs:service:DesiredCount \
  --min-capacity 2 \
  --max-capacity 10 \
  --region us-east-1
```

#### 2. Create Scaling Policy (Target Tracking)
```bash
aws application-autoscaling put-scaling-policy \
  --service-namespace ecs \
  --resource-id service/resortslite-cluster/resortslite-service \
  --scalable-dimension ecs:service:DesiredCount \
  --policy-name cpu-scaling-policy \
  --policy-type TargetTrackingScaling \
  --target-tracking-scaling-policy-configuration '{
    "TargetValue": 70.0,
    "PredefinedMetricSpecification": {
      "PredefinedMetricType": "ECSServiceAverageCPUUtilization"
    },
    "ScaleInCooldown": 300,
    "ScaleOutCooldown": 60
  }' \
  --region us-east-1
```

#### 3. Create Scaling Policy (Step Scaling)
```bash
aws application-autoscaling put-scaling-policy \
  --service-namespace ecs \
  --resource-id service/resortslite-cluster/resortslite-service \
  --scalable-dimension ecs:service:DesiredCount \
  --policy-name memory-scaling-policy \
  --policy-type StepScaling \
  --step-scaling-policy-configuration '{
    "AdjustmentType": "PercentChangeInCapacity",
    "StepAdjustments": [
      {
        "MetricIntervalLowerBound": 0,
        "MetricIntervalUpperBound": 10,
        "ScalingAdjustment": 10
      },
      {
        "MetricIntervalLowerBound": 10,
        "ScalingAdjustment": 30
      }
    ],
    "Cooldown": 60
  }' \
  --region us-east-1
```

### Manual Scaling

```bash
# Scale up
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --desired-count 5 \
  --region us-east-1

# Scale down
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --desired-count 2 \
  --region us-east-1
```

### Blue/Green Deployments

#### 1. Create CodeDeploy Application
```bash
aws deploy create-application \
  --application-name resortslite-app \
  --compute-platform ECS \
  --region us-east-1
```

#### 2. Create Deployment Group
```bash
aws deploy create-deployment-group \
  --application-name resortslite-app \
  --deployment-group-name resortslite-dg \
  --service-role-arn arn:aws:iam::123456789012:role/CodeDeployServiceRole \
  --ecs-services clusterName=resortslite-cluster,serviceName=resortslite-service \
  --load-balancer-info targetGroupPairInfoList=[{targetGroups=[{name=resortslite-tg-blue},{name=resortslite-tg-green}],prodTrafficRoute={listenerArns=[arn:aws:elasticloadbalancing:...]}}] \
  --deployment-config-name CodeDeployDefault.ECSAllAtOnce \
  --region us-east-1
```

#### 3. Create Deployment
```bash
aws deploy create-deployment \
  --application-name resortslite-app \
  --deployment-group-name resortslite-dg \
  --revision '{
    "revisionType": "AppSpecContent",
    "appSpecContent": {
      "content": "{\"version\":0.0,\"Resources\":[{\"TargetService\":{\"Type\":\"AWS::ECS::Service\",\"Properties\":{\"TaskDefinition\":\"resortslite-task:2\",\"LoadBalancerInfo\":{\"ContainerName\":\"resortslite\",\"ContainerPort\":8080}}}}]}"
    }
  }' \
  --region us-east-1
```

### Task Management

```bash
# Stop a specific task
aws ecs stop-task \
  --cluster resortslite-cluster \
  --task <task-id> \
  --reason "Manual restart" \
  --region us-east-1

# Run a one-off task
aws ecs run-task \
  --cluster resortslite-cluster \
  --task-definition resortslite-task \
  --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[subnet-xxxxxxxx],securityGroups=[sg-xxxxxxxxx],assignPublicIp=ENABLED}" \
  --region us-east-1
```

---

## Security Considerations

### 1. Network Security

**VPC Configuration:**
- Use private subnets for tasks (with NAT Gateway for outbound access)
- Restrict security group rules to minimum required ports
- Use VPC endpoints for AWS services (ECR, S3, CloudWatch)

**Security Group Rules:**
```bash
# Allow inbound only from ALB security group
aws ec2 authorize-security-group-ingress \
  --group-id sg-task-sg \
  --protocol tcp \
  --port 8080 \
  --source-group sg-alb-sg \
  --region us-east-1
```

### 2. IAM Permissions

**Principle of Least Privilege:**
- Task Execution Role: Only ECR pull and CloudWatch write permissions
- Task Role: Only permissions required by application (S3, DynamoDB, etc.)

**Example Task Role Policy:**
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:PutObject"
      ],
      "Resource": "arn:aws:s3:::resorts-reports/*"
    }
  ]
}
```

### 3. Secrets Management

**Use AWS Secrets Manager:**
```bash
# Store database password
aws secretsmanager create-secret \
  --name resortslite/db-password \
  --secret-string "secure-password" \
  --region us-east-1

# Reference in task definition
{
  "secrets": [
    {
      "name": "DB_PASSWORD",
      "valueFrom": "arn:aws:secretsmanager:us-east-1:123456789012:secret:resortslite/db-password"
    }
  ]
}
```

### 4. Container Security

**Best Practices:**
- Use non-root user in Dockerfile (already implemented)
- Scan images for vulnerabilities (AWS ECR image scanning)
- Keep base images updated
- Minimize image size (multi-stage builds)

**Enable ECR Image Scanning:**
```bash
aws ecr put-image-scanning-configuration \
  --repository-name resortslite \
  --image-scanning-configuration scanOnPush=true \
  --region us-east-1
```

### 5. Encryption

**Encryption at Rest:**
- Enable encryption for CloudWatch Logs
- Use encrypted EBS volumes for Fargate tasks (automatic)
- Enable S3 bucket encryption

**Encryption in Transit:**
- Use HTTPS for ALB listeners
- Enable TLS for Redis connections
- Use SSL for database connections

**Enable CloudWatch Logs Encryption:**
```bash
aws logs associate-kms-key \
  --log-group-name /ecs/resortslite \
  --kms-key-id arn:aws:kms:us-east-1:123456789012:key/xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx \
  --region us-east-1
```

### 6. Compliance and Auditing

**Enable CloudTrail:**
```bash
aws cloudtrail create-trail \
  --name resortslite-trail \
  --s3-bucket-name resortslite-cloudtrail-logs \
  --is-multi-region-trail \
  --region us-east-1
```

**Enable VPC Flow Logs:**
```bash
aws ec2 create-flow-logs \
  --resource-type VPC \
  --resource-ids vpc-xxxxxxxxx \
  --traffic-type ALL \
  --log-destination-type cloud-watch-logs \
  --log-group-name /aws/vpc/flowlogs \
  --deliver-logs-permission-arn arn:aws:iam::123456789012:role/VPCFlowLogsRole \
  --region us-east-1
```

---

## Technology-Specific Notes

### Spring Boot Configuration

#### 1. JVM Tuning for Containers

**Recommended JVM Options:**
```bash
JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Djava.security.egd=file:/dev/./urandom"
```

**Explanation:**
- `-Xmx512m`: Maximum heap size (adjust based on container memory)
- `-Xms256m`: Initial heap size
- `-XX:+UseContainerSupport`: Enable container awareness
- `-XX:MaxRAMPercentage=75.0`: Use 75% of container memory for heap
- `-XX:+UseG1GC`: Use G1 garbage collector (better for containers)
- `-XX:MaxGCPauseMillis=200`: Target max GC pause time
- `-Djava.security.egd=file:/dev/./urandom`: Faster startup (non-blocking entropy)

#### 2. Spring Boot Actuator

**Health Check Endpoints:**
- `/actuator/health`: Overall application health
- `/actuator/health/liveness`: Liveness probe (for Kubernetes)
- `/actuator/health/readiness`: Readiness probe (for Kubernetes)
- `/actuator/info`: Application information
- `/actuator/metrics`: Application metrics

**Configure Actuator:**
```properties
# Enable health endpoints
management.endpoints.web.exposure.include=health,info,metrics
management.endpoint.health.show-details=always
management.health.redis.enabled=true

# Customize health check
management.health.diskspace.enabled=true
management.health.diskspace.threshold=10MB
```

#### 3. Spring Profiles

**Profile-Specific Configuration:**
```properties
# application-docker.properties
spring.profiles.active=docker
logging.level.root=INFO
logging.level.com.demo.resortslite=DEBUG

# application-production.properties
spring.profiles.active=production
logging.level.root=WARN
logging.level.com.demo.resortslite=INFO
```

**Activate Profile:**
```bash
# Via environment variable
SPRING_PROFILES_ACTIVE=production

# Via JVM argument
JAVA_OPTS="-Dspring.profiles.active=production"
```

#### 4. Logging Configuration

**Logback Configuration (logback-spring.xml):**
```xml
<configuration>
  <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    <encoder>
      <pattern>%d{yyyy-MM-dd HH:mm:ss} - %msg%n</pattern>
    </encoder>
  </appender>
  
  <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
  </appender>
  
  <root level="INFO">
    <appender-ref ref="JSON"/>
  </root>
</configuration>
```

#### 5. Graceful Shutdown

**Configure Graceful Shutdown:**
```properties
# application.properties
server.shutdown=graceful
spring.lifecycle.timeout-per-shutdown-phase=30s
```

**Dockerfile Signal Handling:**
```dockerfile
# Already implemented in Dockerfile
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

#### 6. Connection Pooling

**HikariCP Configuration:**
```properties
# Database connection pool
spring.datasource.hikari.maximum-pool-size=10
spring.datasource.hikari.minimum-idle=5
spring.datasource.hikari.connection-timeout=30000
spring.datasource.hikari.idle-timeout=600000
spring.datasource.hikari.max-lifetime=1800000
```

**Redis Connection Pool:**
```properties
# Redis connection pool
spring.redis.lettuce.pool.max-active=8
spring.redis.lettuce.pool.max-idle=8
spring.redis.lettuce.pool.min-idle=2
spring.redis.lettuce.pool.max-wait=-1ms
```

### Maven Build Optimization

**Dependency Caching:**
```dockerfile
# Copy pom.xml first for dependency caching
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Then copy source code
COPY src ./src
RUN mvn clean package -DskipTests -B
```

**Build Performance:**
- Use `mvn dependency:go-offline` to download dependencies once
- Skip tests during Docker build (`-DskipTests`)
- Use Maven daemon for faster builds (local development)

### Java 8 Considerations

**Compatibility:**
- Spring Boot 2.7.x is the last version supporting Java 8
- Consider upgrading to Java 11 or 17 for better performance and security
- Use Eclipse Temurin (formerly AdoptOpenJDK) for production

**Migration Path:**
- Java 8 → Java 11: Minimal changes required
- Java 8 → Java 17: Review deprecated APIs and modules

---

## Appendix

### A. Useful AWS CLI Commands

```bash
# List all ECS clusters
aws ecs list-clusters --region us-east-1

# List services in a cluster
aws ecs list-services --cluster resortslite-cluster --region us-east-1

# Describe service
aws ecs describe-services --cluster resortslite-cluster --services resortslite-service --region us-east-1

# List task definitions
aws ecs list-task-definitions --family-prefix resortslite --region us-east-1

# Deregister old task definitions
aws ecs deregister-task-definition --task-definition resortslite-task:1 --region us-east-1

# Delete service
aws ecs delete-service --cluster resortslite-cluster --service resortslite-service --force --region us-east-1

# Delete cluster
aws ecs delete-cluster --cluster resortslite-cluster --region us-east-1
```

### B. Docker Commands

```bash
# Build image
docker build -t resortslite:latest .

# Run container locally
docker run -p 8080:8080 -e SPRING_PROFILES_ACTIVE=docker resortslite:latest

# View container logs
docker logs -f <container-id>

# Execute command in container
docker exec -it <container-id> /bin/sh

# Inspect container
docker inspect <container-id>

# Remove all stopped containers
docker container prune

# Remove unused images
docker image prune -a
```

### C. Troubleshooting Checklist

- [ ] Verify AWS credentials are configured
- [ ] Check VPC and subnet configuration
- [ ] Verify security group rules allow required traffic
- [ ] Confirm IAM roles have correct permissions
- [ ] Ensure ECR repository exists and image is pushed
- [ ] Verify task definition has valid CPU/memory combination
- [ ] Check CloudWatch logs for application errors
- [ ] Verify environment variables are set correctly
- [ ] Confirm health check endpoint is accessible
- [ ] Check target group health status (if using ALB)

### D. Performance Tuning

**JVM Tuning:**
- Adjust heap size based on workload
- Use G1GC for better pause times
- Enable JMX for monitoring
- Configure GC logging

**Spring Boot Tuning:**
- Enable connection pooling
- Configure thread pools
- Use caching where appropriate
- Optimize database queries

**Container Tuning:**
- Right-size CPU and memory
- Use appropriate Fargate platform version
- Enable container insights
- Monitor resource utilization

### E. Cost Optimization

**Fargate Pricing:**
- Charged per vCPU and memory per second
- Use Fargate Spot for non-critical workloads
- Right-size tasks to avoid over-provisioning

**Cost Reduction Strategies:**
- Use auto-scaling to match demand
- Schedule tasks for off-peak hours
- Use reserved capacity for predictable workloads
- Monitor and optimize resource usage

**Example Cost Calculation:**
```
Task Configuration: 0.5 vCPU, 1GB memory
Running 24/7 for 1 month (730 hours)

vCPU cost: 0.5 * $0.04048 * 730 = $14.78
Memory cost: 1 * $0.004445 * 730 = $3.24
Total monthly cost: $18.02 per task
```

---

## Support and Resources

### Documentation
- [AWS ECS Documentation](https://docs.aws.amazon.com/ecs/)
- [AWS Fargate Documentation](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/AWS_Fargate.html)
- [Spring Boot Documentation](https://docs.spring.io/spring-boot/docs/current/reference/html/)
- [Docker Documentation](https://docs.docker.com/)

### Community
- [AWS Forums](https://forums.aws.amazon.com/)
- [Stack Overflow](https://stackoverflow.com/questions/tagged/amazon-ecs)
- [Spring Community](https://spring.io/community)

### Tools
- [AWS CLI](https://aws.amazon.com/cli/)
- [AWS Copilot](https://aws.github.io/copilot-cli/)
- [ECS CLI](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/ECS_CLI.html)
- [Docker Desktop](https://www.docker.com/products/docker-desktop)

---

## Conclusion

This deployment guide provides comprehensive instructions for deploying ResortsLite to AWS ECS Fargate. Follow the steps carefully, and refer to the troubleshooting section if you encounter any issues.

For production deployments, ensure you:
- Use private subnets with NAT Gateway
- Enable encryption at rest and in transit
- Implement proper monitoring and alerting
- Configure auto-scaling based on metrics
- Use AWS Secrets Manager for sensitive data
- Enable CloudTrail and VPC Flow Logs for auditing
- Regularly update base images and dependencies
- Perform security scans on container images

**Next Steps:**
1. Build and push Docker image using `build-push.sh`
2. Deploy to ECS Fargate using `deploy-image.sh`
3. Configure monitoring and alerting
4. Set up auto-scaling policies
5. Implement CI/CD pipeline for automated deployments

For questions or issues, refer to the AWS documentation or contact your DevOps team.

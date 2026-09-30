# AWS Deployment Configuration Guide

## Overview
This guide explains how to configure the ResortsLite application for AWS cloud deployment with proper externalized configuration using AWS Systems Manager Parameter Store and environment variables.

---

## Fixed Cloud Readiness Issues

### cr-java-0077: Hard-coded Ports (FIXED)
**Status:** ✅ RESOLVED

**Changes Made:**
1. **ReportService.java (Line 28):** Changed from `@Value("${server.port:8080}")` to `@Value("${SERVER_PORT}")`
2. **application.properties (Lines 5-8):** Updated port configuration to use environment variable with fallback

**Before:**
```java
@Value("${server.port:8080}")
private int serverPort;
```

**After:**
```java
@Value("${SERVER_PORT}")
private int serverPort;
```

---

## AWS Parameter Store Configuration

### Step 1: Create Parameters in AWS Systems Manager Parameter Store

Use the AWS CLI or Console to create the following parameters:

```bash
# Create SERVER_PORT parameter
aws ssm put-parameter \
    --name "/resortslite/prod/SERVER_PORT" \
    --value "8080" \
    --type "String" \
    --description "Application server port for ResortsLite" \
    --region us-east-1

# Create other required parameters
aws ssm put-parameter \
    --name "/resortslite/prod/AWS_S3_BUCKET_NAME" \
    --value "resorts-reports-bucket" \
    --type "String" \
    --region us-east-1

aws ssm put-parameter \
    --name "/resortslite/prod/AWS_REGION" \
    --value "us-east-1" \
    --type "String" \
    --region us-east-1
```

### Step 2: IAM Role Configuration

Ensure your ECS Task Role or EKS Service Account has the following permissions:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "ssm:GetParameter",
        "ssm:GetParameters",
        "ssm:GetParametersByPath"
      ],
      "Resource": [
        "arn:aws:ssm:us-east-1:*:parameter/resortslite/*"
      ]
    }
  ]
}
```

---

## Deployment Options

### Option 1: AWS ECS (Elastic Container Service)

#### Task Definition Configuration

```json
{
  "family": "resortslite-task",
  "taskRoleArn": "arn:aws:iam::ACCOUNT_ID:role/resortslite-task-role",
  "executionRoleArn": "arn:aws:iam::ACCOUNT_ID:role/ecsTaskExecutionRole",
  "containerDefinitions": [
    {
      "name": "resortslite",
      "image": "ACCOUNT_ID.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest",
      "portMappings": [
        {
          "containerPort": 8080,
          "protocol": "tcp"
        }
      ],
      "environment": [
        {
          "name": "SPRING_PROFILES_ACTIVE",
          "value": "prod"
        }
      ],
      "secrets": [
        {
          "name": "SERVER_PORT",
          "valueFrom": "arn:aws:ssm:us-east-1:ACCOUNT_ID:parameter/resortslite/prod/SERVER_PORT"
        },
        {
          "name": "AWS_S3_BUCKET_NAME",
          "valueFrom": "arn:aws:ssm:us-east-1:ACCOUNT_ID:parameter/resortslite/prod/AWS_S3_BUCKET_NAME"
        },
        {
          "name": "AWS_REGION",
          "valueFrom": "arn:aws:ssm:us-east-1:ACCOUNT_ID:parameter/resortslite/prod/AWS_REGION"
        }
      ]
    }
  ]
}
```

### Option 2: AWS EKS (Elastic Kubernetes Service)

#### Kubernetes Deployment with External Secrets Operator

1. **Install External Secrets Operator:**
```bash
helm repo add external-secrets https://charts.external-secrets.io
helm install external-secrets external-secrets/external-secrets -n external-secrets-system --create-namespace
```

2. **Create SecretStore:**
```yaml
apiVersion: external-secrets.io/v1beta1
kind: SecretStore
metadata:
  name: aws-parameter-store
  namespace: resortslite
spec:
  provider:
    aws:
      service: ParameterStore
      region: us-east-1
      auth:
        jwt:
          serviceAccountRef:
            name: resortslite-sa
```

3. **Create ExternalSecret:**
```yaml
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata:
  name: resortslite-config
  namespace: resortslite
spec:
  refreshInterval: 1h
  secretStoreRef:
    name: aws-parameter-store
    kind: SecretStore
  target:
    name: resortslite-secret
    creationPolicy: Owner
  data:
    - secretKey: SERVER_PORT
      remoteRef:
        key: /resortslite/prod/SERVER_PORT
    - secretKey: AWS_S3_BUCKET_NAME
      remoteRef:
        key: /resortslite/prod/AWS_S3_BUCKET_NAME
    - secretKey: AWS_REGION
      remoteRef:
        key: /resortslite/prod/AWS_REGION
```

4. **Deployment Configuration:**
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: resortslite
  namespace: resortslite
spec:
  replicas: 3
  selector:
    matchLabels:
      app: resortslite
  template:
    metadata:
      labels:
        app: resortslite
    spec:
      serviceAccountName: resortslite-sa
      containers:
      - name: resortslite
        image: ACCOUNT_ID.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest
        ports:
        - containerPort: 8080
        envFrom:
        - secretRef:
            name: resortslite-secret
        resources:
          requests:
            memory: "512Mi"
            cpu: "250m"
          limits:
            memory: "1Gi"
            cpu: "500m"
```

### Option 3: AWS Elastic Beanstalk

#### .ebextensions/environment.config

```yaml
option_settings:
  aws:elasticbeanstalk:application:environment:
    SERVER_PORT: '`{"Ref": "AWSEBServerPort"}`'
    AWS_S3_BUCKET_NAME: '`{"Fn::GetAtt": ["AWSEBParameterStoreS3Bucket", "Value"]}`'
    AWS_REGION: '`{"Ref": "AWS::Region"}`'
```

---

## Environment Variables Reference

| Variable Name | Description | Example Value | Required |
|---------------|-------------|---------------|----------|
| SERVER_PORT | Application server port | 8080 | Yes |
| AWS_S3_BUCKET_NAME | S3 bucket for reports | resorts-reports-bucket | Yes |
| AWS_REGION | AWS region | us-east-1 | Yes |
| AWS_SECRET_NAME | Secrets Manager secret name | resorts-db-credentials | Yes |

---

## Testing the Configuration

### Local Testing with Environment Variables

```bash
# Set environment variables
export SERVER_PORT=8080
export AWS_S3_BUCKET_NAME=resorts-reports-bucket
export AWS_REGION=us-east-1
export AWS_SECRET_NAME=resorts-db-credentials

# Run the application
mvn spring-boot:run
```

### Verify Configuration

```bash
# Check if the application is using the correct port
curl http://localhost:8080/actuator/health

# Verify system info endpoint
curl http://localhost:8080/api/bookings/system-info
```

Expected response should show:
```json
{
  "s3BucketName": "resorts-reports-bucket",
  "s3Region": "us-east-1",
  "serverPort": 8080,
  "generatedAt": "2024-01-15 10:30:45"
}
```

---

## Migration Checklist

- [x] Remove hard-coded port from ReportService.java
- [x] Update application.properties to use environment variables
- [x] Document AWS Parameter Store configuration
- [x] Create IAM policies for Parameter Store access
- [x] Provide deployment examples for ECS, EKS, and Elastic Beanstalk
- [ ] Create Parameter Store parameters in AWS account
- [ ] Configure IAM roles with appropriate permissions
- [ ] Deploy application to AWS environment
- [ ] Verify environment variable injection
- [ ] Test application functionality in cloud environment

---

## Troubleshooting

### Issue: Application fails to start with "Could not resolve placeholder 'SERVER_PORT'"

**Solution:** Ensure the SERVER_PORT environment variable is set or the Parameter Store parameter exists and the IAM role has access.

```bash
# Check if parameter exists
aws ssm get-parameter --name "/resortslite/prod/SERVER_PORT" --region us-east-1

# Verify IAM role permissions
aws iam get-role-policy --role-name resortslite-task-role --policy-name SSMParameterAccess
```

### Issue: Port conflict in container orchestration

**Solution:** Use dynamic port mapping in ECS or NodePort/LoadBalancer in EKS. The SERVER_PORT environment variable controls the internal application port, while the container orchestrator manages external port mapping.

---

## Additional Resources

- [AWS Systems Manager Parameter Store Documentation](https://docs.aws.amazon.com/systems-manager/latest/userguide/systems-manager-parameter-store.html)
- [ECS Task Definition Parameters](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_definition_parameters.html)
- [External Secrets Operator for EKS](https://external-secrets.io/latest/)
- [Spring Boot Externalized Configuration](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.external-config)

---

## Support

For issues or questions regarding AWS deployment configuration, please contact the DevOps team or refer to the internal cloud migration documentation.

# ResortsLite — Deployment Guide (AWS EKS)

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Structure](#project-structure)
4. [Local Development with Docker Compose](#local-development-with-docker-compose)
5. [Building and Pushing the Docker Image](#building-and-pushing-the-docker-image)
6. [AWS EKS Deployment](#aws-eks-deployment)
7. [Kubernetes Manifest Reference](#kubernetes-manifest-reference)
8. [Environment Variables Reference](#environment-variables-reference)
9. [Health Checks and Monitoring](#health-checks-and-monitoring)
10. [Scaling and Rolling Updates](#scaling-and-rolling-updates)
11. [Troubleshooting](#troubleshooting)
12. [Security Considerations](#security-considerations)

---

## Overview

**ResortsLite** is a Spring Boot 2.7.x / Java 8 resort-booking REST API.  
It uses:
- **Spring Boot Actuator** for health/readiness probes (`/actuator/health`)
- **Spring Session + Redis** (Amazon ElastiCache) for distributed session management
- **Amazon S3** for report storage
- **H2 in-memory database** (development) / external JDBC datasource (production)

Runtime base image: `amazoncorretto:8`  
Build image: `maven:3.9.4-eclipse-temurin-8`

---

## Prerequisites

### Local Development
| Tool | Minimum Version |
|------|----------------|
| Docker | 24.x |
| Docker Compose | 2.x |
| Java JDK | 8 |
| Apache Maven | 3.9.x |

### AWS EKS Deployment
| Tool | Minimum Version |
|------|----------------|
| AWS CLI | 2.x |
| kubectl | 1.28+ |
| eksctl | 0.170+ (optional, for cluster creation) |
| Docker | 24.x |

### IAM Permissions Required
- `ecr:GetAuthorizationToken`, `ecr:BatchCheckLayerAvailability`, `ecr:PutImage`, `ecr:InitiateLayerUpload`, `ecr:UploadLayerPart`, `ecr:CompleteLayerUpload`, `ecr:CreateRepository`
- `eks:DescribeCluster`, `eks:ListClusters`
- `sts:GetCallerIdentity`

---

## Project Structure

```
ResortsLite-container/
├── Dockerfile                  # Multi-stage build (builder + amazoncorretto:8 runtime)
├── docker-compose.yml          # Local development (application only)
├── .dockerignore               # Excludes target/, wrapper scripts, IDE files
├── pom.xml                     # Maven build descriptor
├── src/
│   └── main/
│       ├── java/com/demo/resortslite/
│       └── resources/
│           └── application.properties
├── kubernetes/
│   ├── namespace.yaml          # Kubernetes namespace: resortsLite
│   ├── deployment.yaml         # Deployment with 2 replicas
│   ├── service.yaml            # ClusterIP service on port 80 → 8080
│   └── ingress.yaml            # AWS ALB Ingress
├── scripts/
│   ├── build-push.sh           # Linux/macOS: build & push to ECR or Docker Hub
│   ├── build-push.bat          # Windows: build & push to ECR or Docker Hub
│   ├── deploy-image.sh         # Linux/macOS: deploy to AWS EKS
│   └── deploy-image.bat        # Windows: deploy to AWS EKS
└── docs/
    └── DEPLOYMENT.md           # This file
```

---

## Local Development with Docker Compose

### 1. Configure environment variables

Create a `.env` file in the project root (never commit this file):

```dotenv
REDIS_HOST=<your-redis-host>
REDIS_PORT=6379
S3_BUCKET_NAME=resorts-reports-bucket
S3_BACKUP_PREFIX=backups/nightly/
PAYMENT_API_ENDPOINT=http://payment-service/payments/charge
```

### 2. Start the application

```bash
docker compose up --build
```

The application will be available at: `http://localhost:8080`

### 3. Verify health

```bash
curl http://localhost:8080/actuator/health
```

### 4. Stop the application

```bash
docker compose down
```

---

## Building and Pushing the Docker Image

### Linux / macOS

```bash
chmod +x scripts/build-push.sh
./scripts/build-push.sh
```

### Windows

```cmd
scripts\build-push.bat
```

The script will prompt you to:
1. Choose a registry (AWS ECR or Docker Hub)
2. Enter registry credentials / AWS account details
3. Specify an image tag (defaults to `latest`)

The script automatically:
- Sanitises the image name to lowercase with hyphens
- Creates the ECR repository if it does not exist (ECR only)
- Builds the Docker image from the project root
- Pushes the image to the selected registry

---

## AWS EKS Deployment

### Step 1 — Configure AWS CLI

```bash
aws configure
# Enter: AWS Access Key ID, Secret Access Key, Default region, Output format
```

### Step 2 — Create or connect to an EKS cluster

**Create a new cluster (optional):**
```bash
eksctl create cluster \
  --name resortsLite-cluster \
  --region us-east-1 \
  --nodegroup-name standard-workers \
  --node-type t3.medium \
  --nodes 2 \
  --nodes-min 1 \
  --nodes-max 4
```

**Connect to an existing cluster:**
```bash
aws eks update-kubeconfig --region us-east-1 --name <your-cluster-name>
```

### Step 3 — Install AWS Load Balancer Controller (for Ingress)

```bash
# Add the EKS chart repository
helm repo add eks https://aws.github.io/eks-charts
helm repo update

# Install the controller
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=<your-cluster-name> \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller
```

### Step 4 — Build and push the image

```bash
./scripts/build-push.sh
# Note the full image URI output (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortsLite:latest)
```

### Step 5 — Deploy to EKS

**Linux / macOS:**
```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

**Windows:**
```cmd
scripts\deploy-image.bat
```

The script will prompt for:
- AWS region and EKS cluster name
- Full Docker image URI
- Application environment variables (REDIS_HOST, REDIS_PORT, S3_BUCKET_NAME, S3_BACKUP_PREFIX, PAYMENT_API_ENDPOINT)

### Step 6 — Verify the deployment

```bash
kubectl get pods,svc,ingress -n resortsLite
kubectl logs -l app=resortsLite -n resortsLite --tail=50
```

---

## Kubernetes Manifest Reference

### namespace.yaml
Creates the `resortsLite` namespace to isolate all application resources.

### deployment.yaml
- **Replicas**: 2 (high availability)
- **Image**: `{{IMAGE_URI}}` — replaced by `deploy-image.sh` at deploy time
- **Resources**: requests `250m CPU / 512Mi RAM`, limits `500m CPU / 1Gi RAM`
- **Liveness probe**: `GET /actuator/health` — starts after 60 s, every 30 s
- **Readiness probe**: `GET /actuator/health` — starts after 30 s, every 15 s
- **Graceful shutdown**: `terminationGracePeriodSeconds: 60`

### service.yaml
- **Type**: ClusterIP
- **Port mapping**: `80 → 8080`
- Routes traffic from the Ingress to the application pods

### ingress.yaml
- **Class**: AWS ALB (internet-facing)
- **Host**: `resortsLite.example.com` — update to your actual domain
- **Health check path**: `/actuator/health`

---

## Environment Variables Reference

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8080` | HTTP port the application listens on |
| `SPRING_PROFILES_ACTIVE` | `docker` | Active Spring profile |
| `REDIS_HOST` | `localhost` | ElastiCache Redis endpoint hostname |
| `REDIS_PORT` | `6379` | ElastiCache Redis port |
| `S3_BUCKET_NAME` | `resorts-reports-bucket` | S3 bucket for report storage |
| `S3_BACKUP_PREFIX` | `backups/nightly/` | S3 key prefix for nightly backups |
| `PAYMENT_API_ENDPOINT` | `http://payment-service/payments/charge` | Payment service URL |
| `JAVA_OPTS` | `-Xms256m -Xmx512m ...` | JVM startup options |
| `TZ` | `UTC` | Container timezone |

---

## Health Checks and Monitoring

### Actuator endpoints

| Endpoint | Purpose |
|----------|---------|
| `GET /actuator/health` | Liveness and readiness probe |
| `GET /actuator/info` | Application metadata |

### Check pod health manually

```bash
kubectl exec -it <pod-name> -n resortsLite -- \
  wget -qO- http://localhost:8080/actuator/health
```

### View application logs

```bash
# All pods
kubectl logs -l app=resortsLite -n resortsLite --tail=100 -f

# Specific pod
kubectl logs <pod-name> -n resortsLite -f
```

---

## Scaling and Rolling Updates

### Manual scaling

```bash
kubectl scale deployment resortsLite --replicas=4 -n resortsLite
```

### Horizontal Pod Autoscaler

```bash
kubectl autoscale deployment resortsLite \
  --cpu-percent=70 \
  --min=2 \
  --max=10 \
  -n resortsLite
```

### Rolling update (new image)

```bash
kubectl set image deployment/resortsLite \
  resortsLite=<new-image-uri> \
  -n resortsLite

kubectl rollout status deployment/resortsLite -n resortsLite
```

### Rollback

```bash
kubectl rollout undo deployment/resortsLite -n resortsLite
kubectl rollout status deployment/resortsLite -n resortsLite
```

---

## Troubleshooting

### Pods stuck in `Pending`
```bash
kubectl describe pod <pod-name> -n resortsLite
# Check: Insufficient CPU/memory, node selector issues, PVC binding
```

### Pods in `CrashLoopBackOff`
```bash
kubectl logs <pod-name> -n resortsLite --previous
# Common causes: missing env vars, Redis unreachable, bad JAVA_OPTS
```

### Ingress not getting an address
```bash
kubectl describe ingress resortsLite-ingress -n resortsLite
# Ensure AWS Load Balancer Controller is installed and IAM permissions are correct
```

### Redis connection failures
- Verify `REDIS_HOST` points to the ElastiCache primary endpoint
- Ensure the EKS node security group allows outbound TCP 6379 to the ElastiCache security group
- Check ElastiCache security group allows inbound TCP 6379 from EKS nodes

### S3 access denied
- Attach an IAM policy with `s3:GetObject`, `s3:PutObject`, `s3:ListBucket` to the EKS node role or pod service account (IRSA)

### OOMKilled pods
- Increase memory limit in `kubernetes/deployment.yaml`
- Tune `JAVA_OPTS`: reduce `-Xmx` or increase container memory limit proportionally

---

## Security Considerations

1. **Non-root container**: The application runs as `appuser` (non-root UID) inside the container.
2. **Secrets management**: Store sensitive values (Redis auth token, DB passwords, API keys) in AWS Secrets Manager or Kubernetes Secrets — never in plain ConfigMaps or environment variables in production.
3. **Image scanning**: Enable ECR image scanning on push to detect CVEs.
4. **Network policies**: Apply Kubernetes NetworkPolicies to restrict pod-to-pod traffic.
5. **IRSA (IAM Roles for Service Accounts)**: Use IRSA instead of node-level IAM roles for fine-grained S3 access control.
6. **TLS**: Configure HTTPS on the ALB Ingress using ACM certificates:
   ```yaml
   alb.ingress.kubernetes.io/certificate-arn: arn:aws:acm:<region>:<account>:certificate/<id>
   alb.ingress.kubernetes.io/listen-ports: '[{"HTTPS": 443}]'
   ```
7. **Dependency vulnerabilities**: The project currently includes `log4j-core:2.14.1` (CVE-2021-44228) and `commons-collections:3.2.1` (CVE-2015-6420). Upgrade these before deploying to production.

---

## Java-Specific Notes

- **JVM container awareness**: `-XX:+UseContainerSupport` and `-XX:MaxRAMPercentage=75.0` ensure the JVM respects container memory limits rather than using host memory.
- **Startup time**: Spring Boot on Java 8 typically takes 15–30 s. The liveness probe `initialDelaySeconds: 60` accounts for this.
- **Graceful shutdown**: `terminationGracePeriodSeconds: 60` gives in-flight requests time to complete before the pod is terminated.
- **Spring profile**: `SPRING_PROFILES_ACTIVE=docker` activates the Docker-specific profile. Add `application-docker.properties` for environment-specific overrides.
- **H2 console**: Disabled in production. Set `spring.h2.console.enabled=false` via environment variable or profile override.

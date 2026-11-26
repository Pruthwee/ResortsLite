# Resortslite Deployment Guide

## Overview

Resortslite is a Spring Boot 2.7.x Java 8 application for resort booking management. This guide covers building, pushing, and deploying the application container to AWS Elastic Kubernetes Service (EKS).

## Prerequisites

### System Requirements
- Java 8 JDK
- Maven 3.6+
- Docker 20.10+
- AWS CLI 2.x
- kubectl 1.21+
- eksctl 0.99+

### AWS Permissions
The deploying user/role needs the following IAM permissions:
- `ecr:GetAuthorizationToken`, `ecr:BatchCheckLayerAvailability`, `ecr:CompleteLayerUpload`, `ecr:UploadLayerPart`, `ecr:InitiateLayerUpload`, `ecr:PutImage`, `ecr:CreateRepository`, `ecr:DescribeRepositories`
- `eks:DescribeCluster`, `eks:ListClusters`
- `sts:GetCallerIdentity`

## Local Setup with Docker Compose

### Prerequisites
- Docker and Docker Compose installed locally
- A Redis instance available (ElastiCache or local)

### Steps
1. Set environment variables:
   ```bash
   export REDIS_HOST=localhost
   export REDIS_PORT=6379
   export REDIS_PASSWORD=
   export PAYMENT_API_ENDPOINT=http://payment-svc:9090/payments/charge
   ```

2. Build and start the application:
   ```bash
   docker-compose up --build
   ```

3. Access the application at `http://localhost:8080`
4. Health check endpoint: `http://localhost:8080/actuator/health`

### Docker Compose Configuration
The `docker-compose.yml` defines a single service (`resortslite`) with:
- Port mapping: `8080:8080`
- Environment variables for Redis connection, payment API endpoint, and server port
- Volume mounts for report and backup directories

## Building and Pushing

### Using build-push.sh (Linux/macOS)
```bash
chmod +x scripts/build-push.sh
./scripts/build-push.sh
```

The script will:
1. Ask you to choose between AWS ECR and Docker Hub
2. For AWS ECR: prompt for AWS region, ECR repository name, and image tag
3. For Docker Hub: prompt for Docker Hub credentials and image tag
4. Log in to the chosen registry
5. Build the Docker image using the multi-stage Dockerfile
6. Push the image to the registry

### Using build-push.bat (Windows)
```cmd
scripts\build-push.bat
```

### Docker Build Details
- **Build stage**: `maven:3.9.4-eclipse-temurin-8` — compiles the project and runs `mvn clean package`
- **Runtime stage**: `amazoncorretto:8` — runs the Spring Boot fat JAR
- The application runs as a non-root user (`appuser`)
- JVM options: `-Xmx512m -Xms256m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0`

## AWS EKS Deployment

### Cluster Setup
1. Create an EKS cluster:
   ```bash
   eksctl create cluster --name resortslite-cluster --region us-east-1 --nodegroup-name resortslite-nodes --node-type t3.medium --nodes 2 --nodes-max 4
   ```

2. Configure kubectl:
   ```bash
   aws eks update-kubeconfig --region us-east-1 --name resortslite-cluster
   ```

3. Install the AWS Load Balancer Controller:
   ```bash
   helm repo add eks https://aws.github.io/eks-helm-charts
   helm install aws-load-balancer-controller eks/aws-load-balancer-controller -n kube-system --set clusterName=resortslite-cluster --set serviceAccount.create=false --set serviceAccount.name=aws-load-balancer-controller
   ```

### Deploy the Application
1. Run the deploy script:
   ```bash
   chmod +x scripts/deploy-image.sh
   ./scripts/deploy-image.sh
   ```

2. The script will prompt for:
   - AWS region
   - EKS cluster name
   - Full image URI (from the build-push step)
   - PAYMENT_API_ENDPOINT value
   - Redis credentials (host, port, password)

3. The script will:
   - Configure kubectl for your EKS cluster
   - Create the `resortslite` namespace
   - Create Kubernetes Secrets for Redis credentials
   - Replace placeholders in the deployment manifest
   - Apply namespace, deployment, service, and ingress manifests
   - Wait for the deployment rollout to complete
   - Display pod, service, and ingress status

### Kubernetes Manifests

#### Namespace (`kubernetes/namespace.yaml`)
Creates the `resortslite` namespace with label `name: resortslite`.

#### Deployment (`kubernetes/deployment.yaml`)
- **Replicas**: 2
- **Container**: `resortslite` with the built image
- **Port**: 8080
- **Environment variables**:
  - `APP_PORT` and `SERVER_PORT`: Set to 8080
  - `REPORT_PATH` and `BACKUP_PATH`: Container paths for file operations
  - `PAYMENT_API_ENDPOINT`: Payment service endpoint (from user input)
  - `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`: From Kubernetes Secret
  - `SPRING_PROFILES_ACTIVE`: `prod`
  - `JAVA_OPTS`: JVM tuning parameters
- **Resources**:
  - Requests: CPU 250m, Memory 512Mi
  - Limits: CPU 500m, Memory 1Gi
- **Liveness probe**: HTTP GET `/actuator/health` on port 8080 (30s initial delay, 10s interval)
- **Readiness probe**: HTTP GET `/actuator/health` on port 8080 (15s initial delay, 10s interval)

#### Service (`kubernetes/service.yaml`)
- **Type**: ClusterIP
- **Port**: 80 → targetPort 8080
- **Selector**: `app: resortslite`

#### Ingress (`kubernetes/ingress.yaml`)
- **Ingress class**: ALB (AWS Load Balancer Controller)
- **Scheme**: Internet-facing
- **Host**: `resortslite.example.com`
- **Path**: `/` (Prefix) → service `resortslite-service` port 80

### Secrets Management
Redis credentials are stored as Kubernetes Secrets:
```bash
kubectl create secret generic resortslite-secrets -n resortslite \
  --from-literal=redis-host=<your-redis-host> \
  --from-literal=redis-port=<your-redis-port> \
  --from-literal=redis-password=<your-redis-password>
```

### Rollback
To rollback a failed deployment:
```bash
kubectl rollout undo deployment/resortslite -n resortslite
```

## Configuration Management

### Environment Variables

| Variable | Description | Default | Source |
|----------|-------------|---------|--------|
| `APP_PORT` | Application port | 8080 | ConfigMap/Env |
| `SERVER_PORT` | Spring Boot server port | 8080 | Env |
| `REPORT_PATH` | Directory for report files | /app/reports | Env |
| `BACKUP_PATH` | Directory for backup files | /app/backups | Env |
| `PAYMENT_API_ENDPOINT` | Payment service API URL | http://payment-svc:9090/payments/charge | Env |
| `REDIS_HOST` | Redis server hostname | localhost | Secret |
| `REDIS_PORT` | Redis server port | 6379 | Secret |
| `REDIS_PASSWORD` | Redis authentication password | (empty) | Secret |
| `SPRING_PROFILES_ACTIVE` | Spring profile | prod | Env |
| `JAVA_OPTS` | JVM options | -Xmx512m -Xms256m ... | Env |

### Spring Boot Configuration
The application uses Spring Boot's externalized configuration. All sensitive values (Redis credentials) are injected via Kubernetes Secrets. Non-sensitive configuration is set via environment variables.

### Database
The application uses H2 in-memory database (configured in `application.properties`). For production, replace with a managed database (Amazon RDS) and inject connection details via environment variables.

## Security Considerations

1. **No hardcoded credentials**: All credentials (Redis password, database passwords) are stored in Kubernetes Secrets, never in source code or Docker images.
2. **Non-root container user**: The application runs as `appuser` (non-root) in the container.
3. **Minimal base image**: The runtime stage uses `amazoncorretto:8` with no package managers.
4. **Health endpoint**: The `/actuator/health` endpoint is used for liveness and readiness probes.
5. **Network policies**: Consider adding Kubernetes Network Policies to restrict ingress traffic.
6. **Image scanning**: Scan the built image for vulnerabilities before pushing to production.

## Java Notes

### JVM Settings
The application runs with the following JVM options optimized for container deployment:
- `-Xmx512m`: Maximum heap size of 512MB
- `-Xms256m`: Initial heap size of 256MB
- `-XX:+UseContainerSupport`: Enables container-aware JVM settings (Java 8u252+)
- `-XX:MaxRAMPercentage=75.0`: Limits heap to 75% of container memory limit

These settings align with the Kubernetes resource limits (512Mi requests, 1Gi limits). The JVM will not exceed the container's memory limits, preventing OOMKilled events.

### Spring Profiles
The application uses the `prod` profile by default (`SPRING_PROFILES_ACTIVE=prod`). This can be overridden via environment variable.

### Health Endpoints
Spring Boot Actuator provides the `/actuator/health` endpoint, used by:
- **Liveness probe**: Determines if the container should be restarted
- **Readiness probe**: Determines if the container should receive traffic

The health endpoint is available from application start-up and remains available throughout the container's lifecycle.

### Session Management
The application uses Spring Session with Redis for distributed session storage. This ensures sessions survive pod restarts and are shared across multiple replicas. Redis connection details are injected via environment variables from Kubernetes Secrets.

### Caching
Local in-memory caching has been replaced with Spring Cache backed by Redis. The `@Cacheable` annotation on `BookingService.getBookingById` caches booking lookups in Redis, ensuring consistency across all pod replicas.

## Troubleshooting

### Pod Failures
- Check pod logs: `kubectl logs <pod-name> -n resortslite`
- Describe pod for events: `kubectl describe pod <pod-name> -n resortslite`
- Common issues: missing secrets, Redis connectivity, port conflicts

### Service Issues
- Verify service endpoints: `kubectl get endpoints -n resortslite`
- Check service description: `kubectl describe svc resortslite-service -n resortslite`

### Ingress Problems
- Check ingress status: `kubectl get ingress -n resortslite`
- Verify ALB controller logs: `kubectl logs -n kube-system -l app.kubernetes.io/name=aws-load-balancer-controller`
- Ensure the ALB security group allows inbound traffic on port 80/443

### Scaling
To manually scale the deployment:
```bash
kubectl scale deployment resortslite --replicas=3 -n resortslite
```

To set up horizontal pod autoscaling:
```bash
kubectl autoscale deployment resortslite --cpu-percent=70 --min=2 --max=10 -n resortslite
```

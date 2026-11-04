# DEPLOYMENT — Resortslite-Con2 (Con2CMP)

## Overview

ResortsLite is a Spring Boot 2.7.x resort booking application built with Java 8 and Maven.
This document covers building, pushing, and deploying the application container to AWS EKS.

## Prerequisites and System Requirements

### Local Development
- Java 8 (JDK 1.8)
- Maven 3.6+
- Docker 20.10+

### AWS EKS Deployment
- AWS CLI 2.x
- kubectl 1.21+
- eksctl 0.90+ (optional, for cluster creation)
- Docker 20.10+
- IAM permissions for ECR, EKS, and Secrets Manager

### Java Notes
- The application runs on Java 8 (amazoncorretto:8 runtime image)
- JVM settings in the container: `-Xmx512m -Xms256m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0`
- These settings are tuned for the container's 1Gi memory limit
- Spring profiles: `SPRING_PROFILES_ACTIVE=prod` is set in the Dockerfile
- Health endpoint: `/actuator/health` (Spring Boot Actuator)

## Local Setup with Docker Compose

1. Set the required environment variables:
```bash
export REDIS_HOST=localhost
export REDIS_PORT=6379
export REPORT_PATH=/app/reports
export BACKUP_PATH=/app/backups
export SERVER_PORT=8080
export PAYMENT_API_URL=http://localhost:9090/payments/charge
export DB_USERNAME=sa
export DB_PASSWORD=
```

2. Build and run with Docker Compose:
```bash
docker-compose up --build
```

3. Access the application:
- Application: http://localhost:8080
- H2 Console: http://localhost:8080/h2-console
- Health check: http://localhost:8080/actuator/health

## Building and Pushing

### Using build-push.sh (Linux/macOS)

```bash
chmod +x scripts/build-push.sh
./scripts/build-push.sh
```

The script will ask for:
1. Target platform: Amazon ECR or Docker Hub
2. Registry details (AWS region + account ID, or Docker Hub credentials)
3. Image tag (defaults to `latest`)

### Using build-push.bat (Windows)

```cmd
scripts\build-push.bat
```

The script follows the same flow as the shell version.

## AWS EKS Deployment

### EKS Cluster Setup

1. Create an EKS cluster (if you don't have one):
```bash
eksctl create cluster --name resortslite-cluster --region us-east-1 --nodegroup-name standard-workers --node-type t3.medium --nodes 2 --nodes-max 4
```

2. Configure kubectl:
```bash
aws eks update-kubeconfig --region us-east-1 --name resortslite-cluster
```

3. Verify cluster access:
```bash
kubectl cluster-info
```

### Deploy the Application

1. Run the deployment script:
```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

2. The script will ask for:
- AWS region
- EKS cluster name
- Full image URI (from build-push.sh)
- Environment variable values (or skip to use defaults)

3. The script will:
- Configure kubectl for your EKS cluster
- Apply the namespace
- Create a Kubernetes Secret for credentials
- Replace placeholders in the deployment manifest
- Apply deployment, service, and ingress
- Wait for rollout to complete

### Kubernetes Manifests

The `kubernetes/` directory contains:
- `namespace.yaml` — Namespace `resortslite`
- `deployment.yaml` — Deployment with 2 replicas, resource limits, and health probes
- `service.yaml` — ClusterIP service on port 80 → 8080
- `ingress.yaml` — ALB ingress for `resortslite.example.com`

### Health Checks

The application exposes `/actuator/health` on port 8080. The Kubernetes deployment configures:
- **Liveness probe**: HTTP GET `/actuator/health` on port 8080, 30s initial delay, 15s interval
- **Readiness probe**: HTTP GET `/actuator/health` on port 8080, 15s initial delay, 10s interval

### Scaling and Management

#### Horizontal Pod Autoscaler (HPA)

To create an HPA:
```bash
kubectl autoscale deployment resortslite --cpu-percent=70 --min=2 --max=10 -n resortslite
```

#### Rolling Updates

```bash
kubectl set image deployment/resortslite resortslite=<new-image-uri> -n resortslite
kubectl rollout status deployment/resortslite -n resortslite
```

#### Rollback

```bash
kubectl rollout undo deployment/resortslite -n resortslite
```

### Troubleshooting

#### Pod Failures
```bash
kubectl describe pod <pod-name> -n resortslite
kubectl logs <pod-name> -n resortslite
```

#### Service Issues
```bash
kubectl get svc -n resortslite
kubectl describe svc resortslite-service -n resortslite
```

#### Ingress Problems
```bash
kubectl describe ingress resortslite-ingress -n resortslite
kubectl get ingress -n resortslite
```

#### Redis Connection Issues
The application requires a Redis instance for session storage and caching.
Ensure `REDIS_HOST` and `REDIS_PORT` are set in the Kubernetes Secret.

## Configuration Management

The following environment variables are used by the application:

| Variable | Description | Default |
|---|---|---|
| `REDIS_HOST` | Redis host for session storage and cache | `localhost` |
| `REDIS_PORT` | Redis port | `6379` |
| `REDIS_PASSWORD` | Redis authentication password | (none) |
| `REPORT_PATH` | Base path for report files | `/app/reports` |
| `BACKUP_PATH` | Base path for backup files | `/app/backups` |
| `SERVER_PORT` | Server port (application logic) | `8080` |
| `PAYMENT_API_URL` | Payment API endpoint URL | `http://localhost:9090/payments/charge` |
| `DB_USERNAME` | Database username | `sa` |
| `DB_PASSWORD` | Database password | (empty) |
| `SPRING_PROFILES_ACTIVE` | Spring profile | `prod` |

## Security Considerations

- Credentials (Redis password, DB username, DB password) are stored in Kubernetes Secrets, never in plain text in manifests
- The application uses HTTPS for external communication (ALB terminates TLS)
- H2 console is enabled for development; disable in production: `spring.h2.console.enabled=false`
- The container runs as a non-root user (UID 1000)
- Health checks use the application's own `/actuator/health` endpoint — no additional tools installed in the container

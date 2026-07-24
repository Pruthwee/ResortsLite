# Deployment Guide - ResortsLite

This guide provides instructions for containerizing and deploying the ResortsLite application to AWS EKS.

## 1. Prerequisites

### System Requirements
- Java 8 JDK
- Maven 3.8+
- Docker
- AWS CLI configured with appropriate IAM permissions
- kubectl installed
- AWS EKS Cluster

## 2. Local Development Setup

### Using Docker Compose
For local testing, you can use the provided `docker-compose.yml` file.

1. Build the image locally:
   ```bash
   docker build -t resortslite:latest .
   ```
2. Start the application:
   ```bash
   docker-compose up -d
   ```
3. Access the application at `http://localhost:8080`.

## 3. Build and Push to Registry

The `scripts/build-push.sh` (Linux/macOS) or `scripts/build-push.bat` (Windows) script automates the build and push process.

### Usage:
1. Make the script executable:
   ```bash
   chmod +x scripts/build-push.sh
   ```
2. Run the script:
   ```bash
   ./scripts/build-push.sh
   ```
3. Follow the interactive prompts to select your registry (AWS ECR or Docker Hub) and provide credentials.

## 4. AWS EKS Deployment

The `scripts/deploy-image.sh` (Linux/macOS) or `scripts/deploy-image.bat` (Windows) script automates the deployment to EKS.

### Usage:
1. Make the script executable:
   ```bash
   chmod +x scripts/deploy-image.sh
   ```
2. Run the script:
   ```bash
   ./scripts/deploy-image.sh
   ```
3. Provide the following information when prompted:
   - AWS Region
   - EKS Cluster Name
   - Full Docker Image URI (from the build-push step)
   - Environment variables (DB_URL, DB_USERNAME, etc.)

### Deployment Order:
The script applies manifests in the following order:
1. `namespace.yaml`: Creates the `resortslite` namespace.
2. `deployment.yaml`: Deploys the application pods with resource limits and health probes.
3. `service.yaml`: Creates a ClusterIP service for internal load balancing.
4. `ingress.yaml`: Configures the AWS Application Load Balancer (ALB) for external access.

## 5. Configuration Management

The application uses environment variables for configuration, which are mapped in the `deployment.yaml` manifest.

| Variable | Description | Default Value |
|---|---|---|
| `SERVER_PORT` | Application port | 8080 |
| `DB_URL` | Database connection URL | `jdbc:h2:mem:resortdb` |
| `DB_USERNAME` | Database username | sa |
| `DB_PASSWORD` | Database password | (empty) |
| `PAYMENT_ENDPOINT` | Payment service URL | `http://payment-svc.internal:9090/charge` |
| `INVENTORY_ENDPOINT` | Inventory service URL | `http://inventory-svc.internal:8081/rooms` |
| `NOTIFICATION_ENDPOINT` | Notification service URL | `http://notify.internal:7070/send` |

## 6. Troubleshooting

### Pod Failures
Check pod logs:
```bash
kubectl logs -l app=resortslite -n resortslite
```
Describe pod for events:
```bash
kubectl describe pod -l app=resortslite -n resortslite
```

### Service/Ingress Issues
Verify service endpoints:
```bash
kubectl get endpoints -n resortslite
```
Check ingress status:
```bash
kubectl get ingress -n resortslite
```

### Rollback
If a deployment fails, you can roll back to the previous version:
```bash
kubectl rollout undo deployment/resortslite -n resortslite
```

## 7. Security Considerations
- The container runs as a non-root user (`appuser`).
- Resource limits are enforced to prevent noisy neighbor issues.
- Sensitive configuration (passwords) should be managed using AWS Secrets Manager or Kubernetes Secrets in production.

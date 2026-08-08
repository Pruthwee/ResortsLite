# restorelitecontainer Deployment Guide

## Overview
This project is a Java 8 Spring Boot 2.7.18 application built with Maven and packaged as an executable JAR. It exposes the main application on port 8080 and Spring Boot Actuator health information on `/actuator/health` through the same port.

## Prerequisites
- Docker 20+
- Docker Compose v2+
- Azure CLI
- kubectl
- Access to an Azure subscription and AKS cluster
- Network connectivity to external services referenced by environment variables

## Application Configuration
The application reads the following important settings:
- `SERVER_PORT` (default `8080`)
- `PAYMENT_API_URL`
- `INVENTORY_API_URL`
- `INVENTORY_SERVICE_PORT`
- `REPORT_BASE_PATH`
- `BACKUP_PATH`
- `SPRING_PROFILES_ACTIVE`
- `JAVA_OPTS`

## Local Development with Docker Compose
1. Review `docker-compose.yml`.
2. Override environment variables as needed in your shell.
3. Start the application:
   ```bash
   docker compose up --build
   ```
4. Access the application at `http://localhost:8080`.
5. Verify health at `http://localhost:8080/actuator/health`.

## Build and Push Docker Image
### Linux/macOS
```bash
chmod +x scripts/build-push.sh
./scripts/build-push.sh
```
The script prompts for registry type, credentials, and image tag, then builds and pushes the image.

### Windows
```bat
scripts\build-push.bat
```
The batch script performs the same workflow for Windows users.

## Dockerfile Notes
- Multi-stage Maven build
- Runtime image uses explicit base image `amazoncorretto:8`
- Non-root execution
- JVM container tuning enabled through `JAVA_OPTS`
- No Docker healthcheck instruction; Kubernetes handles probes

## Azure AKS Deployment
### Prepare Cluster Access
1. Sign in to Azure:
   ```bash
   az login
   ```
2. Ensure the target AKS cluster exists and your account has access.

### Deploy from Linux/macOS
```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```
You will be prompted for:
- Azure resource group
- AKS cluster name
- Full image URI
- Optional application environment variables

### Deploy from Windows
```bat
scripts\deploy-image.bat
```

## Kubernetes Manifests
- `kubernetes/namespace.yaml`: dedicated namespace
- `kubernetes/deployment.yaml`: 2 replicas, resource requests/limits, environment placeholders, liveness/readiness probes
- `kubernetes/service.yaml`: ClusterIP service exposing port 80 to container port 8080
- `kubernetes/ingress.yaml`: Azure Application Gateway ingress definition

## Deployment Verification
After deployment, verify:
```bash
kubectl get pods,svc,ingress -n restorelitecontainer
kubectl rollout status deployment/restorelitecontainer -n restorelitecontainer
```

## Scaling and Operations
- Scale replicas:
  ```bash
  kubectl scale deployment/restorelitecontainer --replicas=3 -n restorelitecontainer
  ```
- Check logs:
  ```bash
  kubectl logs -l app=restorelitecontainer -n restorelitecontainer
  ```
- Roll back:
  ```bash
  kubectl rollout undo deployment/restorelitecontainer -n restorelitecontainer
  ```

## Troubleshooting
- **Image pull failures**: verify registry login, image URI, and AKS pull permissions.
- **Probe failures**: confirm `/actuator/health` is exposed and application startup completed.
- **Ingress issues**: verify Azure Application Gateway Ingress Controller is installed and DNS points to the gateway.
- **External dependency failures**: validate `PAYMENT_API_URL`, `INVENTORY_API_URL`, and filesystem paths.

## Security Considerations
- Store sensitive values in Kubernetes Secrets instead of plain manifests for production.
- Restrict ingress hostnames and TLS configuration before internet exposure.
- Review legacy dependencies and remediate known vulnerabilities before production rollout.
- Use Azure RBAC and least-privilege access for AKS operations.

## Java-Specific Notes
- Java version: 8
- Framework: Spring Boot 2.7.18
- Build tool: Maven
- Packaging: executable JAR
- Recommended JVM settings are already included in Docker and Kubernetes artifacts.

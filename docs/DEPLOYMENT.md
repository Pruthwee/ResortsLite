# Resortlite_Mono2 - Deployment Guide (AWS EKS)

ResortsLite is a Spring Boot 2.7.18 / Java 8 REST application (booking API) packaged as a runnable jar. This guide covers building the container, running it locally and deploying it to Amazon EKS.

## 1. Prerequisites and system requirements

| Tool | Use |
|---|---|
| Docker 20.10+ | build and run the image |
| AWS CLI v2 | ECR login, `aws eks update-kubeconfig` |
| kubectl (within one minor version of the cluster) | apply manifests |
| eksctl | create and manage the EKS cluster |
| Java 8 and Maven 3.8+ | only for building outside Docker |

IAM permissions needed: `ecr:*` on the repository (create, push), `eks:DescribeCluster`, and an EKS access entry or `aws-auth` mapping that gives your IAM identity rights in the cluster. Recommended pod size: 250m-500m CPU, 512Mi-1Gi memory (as in `kubernetes/deployment.yaml`).

The application needs **Redis** (for example Amazon ElastiCache) for HTTP sessions (Spring Session) and the booking cache. Redis is not part of this repository: it is provided separately and reached through environment variables.

## 2. Local setup with Docker Compose

`docker-compose.yml` runs only the application. Export the variables first (credentials are never written in the file):

```bash
export REDIS_HOST=my-redis.example.internal
export REDIS_PASSWORD='your-auth-token'     # may be a placeholder for a Redis without AUTH, but must be set
docker compose up --build
curl http://localhost:8080/actuator/health
```

The application's own database is the in-memory H2 configured in `application.properties` (unchanged by this containerization). Reports are written to the `reports-data` volume (`/app/reports`), backups to `backups-data`.

## 3. Build and push the image

Run from the repository root:

```bash
./scripts/build-push.sh        # Linux / macOS
scripts\build-push.bat         # Windows
```

The script asks for the registry (1 = AWS ECR, 2 = Docker Hub), then the details of the chosen one (region and account ID for ECR; user name and password/token for Docker Hub), then the image tag (empty means `latest`). For ECR it logs in with `aws ecr get-login-password`, creates the repository if it is missing, builds with `docker build -f Dockerfile -t <registry>/<repo>:<tag> .` and pushes. The image name is `resortlite-mono2`.

## 4. Deploying to AWS EKS

### 4.1 Cluster setup and kubectl configuration

```bash
eksctl create cluster --name my-cluster --region us-east-1 --nodes 2
aws eks update-kubeconfig --region us-east-1 --name my-cluster
kubectl cluster-info
```

Install the **AWS Load Balancer Controller** in the cluster (needed by the Ingress annotations `kubernetes.io/ingress.class: alb` and `alb.ingress.kubernetes.io/scheme: internet-facing`). Create an ElastiCache for Redis cluster in the same VPC and allow port 6379 from the node/pod security group. Use TLS (`REDIS_SSL=true`) if in-transit encryption is on.

### 4.2 Deploy with the script

```bash
./scripts/deploy-image.sh      # or scripts\deploy-image.bat on Windows
```

It asks for the region, the cluster name, the image URI (full path with registry and tag) and the environment variables `PAYMENT_API_URL`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_SSL` and the credential `REDIS_PASSWORD` (hidden input in the `.sh`; the `.bat` cannot hide it). It then configures kubectl, applies the namespace, creates the Secret `resortlite-mono2-secrets`, fills the `{{...}}` placeholders of `deployment.yaml` (the template file is restored afterwards), applies the manifests in order, waits with `kubectl rollout status deployment/resortlite-mono2 -n resortlite-mono2` and shows pods, service, ingress and the URL.

### 4.3 The manifests

| File | Content |
|---|---|
| `kubernetes/namespace.yaml` | Namespace `resortlite-mono2` (label `name: resortlite-mono2`) |
| `kubernetes/deployment.yaml` | Deployment `resortlite-mono2`, 2 replicas, container port 8080, env vars, `REDIS_PASSWORD` from Secret `resortlite-mono2-secrets`, requests 250m/512Mi, limits 500m/1Gi, liveness `/actuator/health/liveness` and readiness `/actuator/health/readiness`, non-root user 1001 with `emptyDir` volumes for `/app/reports` and `/app/backups` |
| `kubernetes/service.yaml` | Service `resortlite-mono2-service`, ClusterIP, port 80 to 8080 |
| `kubernetes/ingress.yaml` | Ingress `resortlite-mono2-ingress` for host `resortlite-mono2.example.com`, ALB annotations |

`emptyDir` volumes are lost when a pod is replaced and are not shared between replicas: mount a persistent volume (for example EFS) if reports must survive.

## 5. Configuration management and environment variables

| Variable | Meaning | Default |
|---|---|---|
| `APP_PORT` | HTTP port (`server.port`) | 8080 |
| `REPORT_PATH` | directory for generated reports | `/app/reports` |
| `BACKUP_PATH` | backup directory | `/app/backups/nightly/` |
| `PAYMENT_API_URL` | payment service endpoint | `http://payment-service:9090/payments/charge` |
| `REDIS_HOST`, `REDIS_PORT` | Redis endpoint | `localhost`, `6379` |
| `REDIS_SSL` | TLS to Redis | `false` |
| `REDIS_PASSWORD` | Redis AUTH token (Secret) | empty |
| `CACHE_TTL_MS` | booking cache TTL | 600000 |
| `SPRING_PROFILES_ACTIVE` | Spring profile | `prod` |
| `JAVA_OPTS` | JVM options | see section 7 |

## 6. Security considerations and best practices

- Credentials only in Kubernetes Secrets (`resortlite-mono2-secrets`); consider AWS Secrets Manager with the External Secrets Operator and IRSA.
- The container runs as non-root user 1001; the runtime image has no package manager steps and no extra tools.
- The H2 console is enabled in `application.properties` (`/h2-console`): disable it (`SPRING_H2_CONSOLE_ENABLED=false`) before exposing the application publicly.
- The pom still declares log4j-core 2.14.1 (Log4Shell) and commons-collections 3.2.1: these were not part of the blockers and were not changed; upgrade them before production.
- The code builds SQL by string concatenation (SQL injection) and uses MD5: not changed here.
- Serve the ingress over HTTPS (ACM certificate annotations `alb.ingress.kubernetes.io/certificate-arn`).
- Security groups must allow the pods to reach ElastiCache on 6379.

## 7. Java notes

- **JVM settings**: `JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"`. With the pod limit of 1Gi, `-Xmx512m` caps the heap and leaves room for metaspace and threads; if you raise the memory limit, raise `-Xmx` (or drop it and rely on `MaxRAMPercentage`) in step. Note that on Java 8 `UseContainerSupport` is only available from 8u191 (amazoncorretto:8 has it). `java` runs as PID 1 through `exec`, so SIGTERM triggers Spring's graceful shutdown.
- **Spring profiles**: `SPRING_PROFILES_ACTIVE=prod` is set in the image and in the Deployment; the project has no profile-specific files, so any value works.
- **Health endpoints**: the application had no health endpoint; `spring-boot-starter-actuator` was added. `/actuator/health` (overall), `/actuator/health/liveness` and `/actuator/health/readiness` are exposed on the application port (only `health` is exposed). Kubernetes probes use the liveness and readiness groups and the ALB health check uses readiness; they do not depend on Redis, whereas `/actuator/health` includes the Redis indicator.
- **State**: sessions (Spring Session, 30 minutes) and the booking cache (10 minutes TTL) live in Redis, so replicas can be scaled. The H2 database is in-memory per pod: data is not shared between replicas and is lost on restart.

## 8. Troubleshooting

**Pod failures**: `kubectl get pods -n resortlite-mono2`, `kubectl describe pod <pod> -n resortlite-mono2`, `kubectl logs <pod> -n resortlite-mono2`. `ImagePullBackOff`: check the image URI and that the node role can pull from ECR. `CrashLoopBackOff`/`OOMKilled`: check memory limits against `JAVA_OPTS`. Probe failures: the first start takes about 30-60 seconds, tune `initialDelaySeconds`. `Unable to connect to Redis` errors on `/api/bookings/create`: check `REDIS_HOST`, `REDIS_SSL` and the security group.

**Service issues**: `kubectl get endpoints resortlite-mono2-service -n resortlite-mono2` must list pod IPs; check the selector `app: resortlite-mono2`, and test with `kubectl port-forward svc/resortlite-mono2-service 8080:80 -n resortlite-mono2`.

**Ingress problems**: `kubectl describe ingress resortlite-mono2-ingress -n resortlite-mono2`; no ADDRESS means the AWS Load Balancer Controller is missing or lacks IAM permissions (`kubectl logs -n kube-system deployment/aws-load-balancer-controller`); check subnet tags; the host rule needs a DNS record for `resortlite-mono2.example.com` pointing to the ALB.

**EKS-specific**: `error: You must be logged in to the server` means your IAM identity is not mapped in the cluster; re-run `aws eks update-kubeconfig` with the right profile.

## 9. Scaling and management

```bash
kubectl scale deployment/resortlite-mono2 --replicas=4 -n resortlite-mono2
kubectl autoscale deployment resortlite-mono2 --cpu-percent=70 --min=2 --max=10 -n resortlite-mono2   # HPA, needs metrics-server
kubectl set image deployment/resortlite-mono2 resortlite-mono2=<new image URI> -n resortlite-mono2     # rolling update
kubectl rollout status deployment/resortlite-mono2 -n resortlite-mono2
kubectl rollout history deployment/resortlite-mono2 -n resortlite-mono2
kubectl rollout undo deployment/resortlite-mono2 -n resortlite-mono2                                   # rollback
```

Use `eksctl scale nodegroup` or Cluster Autoscaler / Karpenter to scale nodes.

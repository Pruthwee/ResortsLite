# ResortsLite deployment guide (Docker, AWS EKS)

ResortsLite is a Spring Boot 2.7.18 web application (Java 8, Maven) that exposes a booking REST API
on port 8080. This guide covers building the image, running it locally with Docker Compose and
deploying it to AWS EKS.

> The application's HTTP sessions and booking cache live in Redis (Amazon ElastiCache). A reachable
> Redis is required at run time; it is not part of this repository or of docker-compose.yml.
> The database is the project's own in-memory H2 (`jdbc:h2:mem:resortdb`), unchanged.

## 1. Prerequisites and system requirements

| Tool | Use |
|---|---|
| Docker 20.10+ | build the image, run Docker Compose |
| AWS CLI v2 | ECR login, `aws eks update-kubeconfig` |
| kubectl (within one minor version of the cluster) | apply the manifests |
| eksctl | create and manage the EKS cluster |
| Bash (Linux, macOS) or cmd (Windows) | the scripts in `scripts/` |
| JDK 8 and Maven 3.8 | only for building outside Docker |

Resources: each pod requests 250m CPU / 512Mi and is limited to 500m CPU / 1Gi. A build needs about 2 GB RAM.

IAM permissions for the user or role running the scripts: `ecr:GetAuthorizationToken`, `ecr:CreateRepository`,
`ecr:DescribeRepositories`, `ecr:BatchCheckLayerAvailability`, `ecr:InitiateLayerUpload`, `ecr:UploadLayerPart`,
`ecr:CompleteLayerUpload`, `ecr:PutImage`, `eks:DescribeCluster`, and a Kubernetes RBAC mapping (aws-auth ConfigMap
or EKS access entry) that lets it create namespaces, secrets, deployments, services and ingresses.

## 2. Local setup with Docker Compose

`docker-compose.yml` starts only the application. Redis and the payment API are reached through environment
variables; nothing else is started. Credentials are read from the shell that runs compose.

```bash
export REDIS_HOST=<redis host reachable from your machine>
export REDIS_PASSWORD=<redis password>
export PAYMENT_API_URL=<payment endpoint url>
# optional: REDIS_PORT (6379), REDIS_SSL (false), SPRING_PROFILES_ACTIVE (default)
docker compose up --build
```

The application listens on http://localhost:8080 and the health endpoint is http://localhost:8080/actuator/health.
The volumes `resortslite-reports` and `resortslite-backups` keep the report and backup folders.

## 3. Build and push the image

Run the scripts from the repository root; they never change directory.

```bash
./scripts/build-push.sh          # Linux, macOS
scripts\build-push.bat           # Windows
```

They ask for the registry (1. AWS ECR, 2. Docker Hub), then only the chosen registry's details
(AWS account ID and region, or the Docker Hub user and password), then `IMAGE_TAG` (empty means `latest`).
The image name is the project name lower-cased: `resortslite`. For ECR the scripts log in with
`aws ecr get-login-password` and create the repository when it is missing. They run
`docker build -f Dockerfile -t <registry>/<repo>:<tag> .` and `docker push`.

## 4. Deploy to AWS EKS

### 4.1 Cluster setup and kubectl configuration

```bash
eksctl create cluster --name resortslite-cluster --region us-east-1 --nodes 2 --node-type t3.medium
aws eks update-kubeconfig --region us-east-1 --name resortslite-cluster
kubectl cluster-info
```

The Ingress needs the AWS Load Balancer Controller in the cluster (IAM OIDC provider, IAM policy, service
account and the `aws-load-balancer-controller` Helm chart; see the AWS documentation). Create an Amazon
ElastiCache for Redis cluster in the same VPC and allow port 6379 from the node security group.

### 4.2 Deploy with the script

```bash
./scripts/deploy-image.sh        # Linux, macOS
scripts\deploy-image.bat         # Windows
```

The script asks for the AWS region, the cluster name, the image URI (registry, repository and tag), and the values of
`REDIS_HOST`, `REDIS_PORT`, `REDIS_SSL`, `PAYMENT_API_URL` and (without echo in the .sh) `REDIS_PASSWORD`.
It then configures kubectl, replaces the `"{{...}}"` placeholders in `kubernetes/deployment.yaml`
(and restores the file afterwards), applies `namespace.yaml`, creates the Secret `resortslite-secrets`, applies
`deployment.yaml`, `service.yaml` and `ingress.yaml`, waits for `kubectl rollout status deployment/resortslite -n resortslite`,
prints `kubectl get pods,svc,ingress -n resortslite` and the address of the load balancer.

### 4.3 The manifests

| File | Content |
|---|---|
| `kubernetes/namespace.yaml` | Namespace `resortslite` with the label `name: resortslite` |
| `kubernetes/deployment.yaml` | Deployment `resortslite`, 2 replicas, container `resortslite` on port 8080, requests 250m/512Mi, limits 500m/1Gi, liveness probe `/actuator/health/liveness`, readiness probe `/actuator/health/readiness`, `emptyDir` volumes for `/app/reports` and `/app/backups`, `REDIS_PASSWORD` from the Secret |
| `kubernetes/service.yaml` | Service `resortslite-service`, ClusterIP, port 80 to the container port 8080 |
| `kubernetes/ingress.yaml` | Ingress `resortslite-ingress` for the host `resortslite.example.com`, AWS Load Balancer Controller annotations (`kubernetes.io/ingress.class: alb`, `alb.ingress.kubernetes.io/scheme: internet-facing`), target type ip, ALB health check on `/actuator/health/readiness` |

Change the host `resortslite.example.com` to your own domain and point its DNS record at the ALB.

## 5. Configuration management and environment variables

| Variable | Purpose | Default | Where it is set |
|---|---|---|---|
| `APP_PORT` | HTTP port (`server.port`) | 8080 | Dockerfile, deployment.yaml |
| `REPORT_PATH` | folder for generated reports | `/app/reports` | Dockerfile, deployment.yaml |
| `BACKUP_PATH` | folder for nightly backups | `/app/backups` | Dockerfile, deployment.yaml |
| `REDIS_HOST` | Redis (ElastiCache) endpoint | `localhost` | `{{REDIS_HOST}}` in deployment.yaml |
| `REDIS_PORT` | Redis port | 6379 | `{{REDIS_PORT}}` |
| `REDIS_SSL` | TLS to Redis (true for in-transit encryption) | false | `{{REDIS_SSL}}` |
| `REDIS_PASSWORD` | Redis AUTH token (credential) | empty | Secret `resortslite-secrets`, key `REDIS_PASSWORD` |
| `PAYMENT_API_URL` | payment service endpoint | none | `{{PAYMENT_API_URL}}` |
| `SPRING_PROFILES_ACTIVE` | Spring profile | `default` | Dockerfile, deployment.yaml |
| `JAVA_OPTS` | JVM options | see Java notes | Dockerfile |

## 6. Security considerations and best practices

- The Redis password is a Kubernetes Secret, never a plain value in the manifests or the image. Consider AWS Secrets Manager with the External Secrets Operator and IRSA.
- The container runs as the non-root user `app`; the image holds no credentials.
- Use TLS (`REDIS_SSL=true`) for ElastiCache with in-transit encryption, and restrict the ElastiCache security group to the cluster.
- Terminate HTTPS at the ALB (add the `alb.ingress.kubernetes.io/certificate-arn` and `listen-ports` annotations).
- The H2 console (`/h2-console`) is enabled in `application.properties` and the project still carries old dependencies (log4j-core 2.14.1, commons-collections 3.2.1) and an SQL string concatenation; these are outside the containerization blockers and should be handled before a production release.
- Scan the image (for example Amazon ECR image scanning) and keep the base image up to date.

## 7. Troubleshooting on EKS

- **Pods failing** (`CrashLoopBackOff`, `ImagePullBackOff`): `kubectl describe pod <pod> -n resortslite`, `kubectl logs <pod> -n resortslite`. For ImagePullBackOff check that the node role can read ECR and that the image URI is right.
- **Readiness probe fails**: `/actuator/health/readiness` needs the application to be up; check the logs and `kubectl exec <pod> -n resortslite -- wget -qO- localhost:8080/actuator/health`. The overall `/actuator/health` also reports Redis; a `DOWN` Redis shows there, and requests that use the session or cache fail until Redis is reachable.
- **Session errors / HTTP 500 on booking calls**: Redis is unreachable. Check `REDIS_HOST`, `REDIS_PORT`, `REDIS_SSL`, the secret `REDIS_PASSWORD`, the security groups and the VPC.
- **Service issues**: `kubectl get endpoints resortslite-service -n resortslite` must list the pod IPs; the selector is `app: resortslite`.
- **Ingress problems**: `kubectl describe ingress resortslite-ingress -n resortslite`; check that the AWS Load Balancer Controller runs (`kubectl get pods -n kube-system`), the subnets carry the `kubernetes.io/role/elb` tag, and the ALB target group is healthy.
- **Report folder not writable**: the folders are `emptyDir` volumes and are lost with the pod; use a persistent volume or S3 for reports that must be kept.

## 8. Scaling and management

```bash
kubectl scale deployment/resortslite -n resortslite --replicas=4
kubectl autoscale deployment resortslite -n resortslite --cpu-percent=70 --min=2 --max=10     # HPA (metrics-server required)
kubectl set image deployment/resortslite resortslite=<new image uri> -n resortslite           # rolling update
kubectl rollout status deployment/resortslite -n resortslite
kubectl rollout history deployment/resortslite -n resortslite
kubectl rollout undo deployment/resortslite -n resortslite                                    # rollback
```

Because sessions are stored in Redis, no sticky sessions are needed and replicas can scale freely. Managed node groups
are scaled with `eksctl scale nodegroup` or the Cluster Autoscaler / Karpenter.

## 9. Java notes

- **JVM settings** (`JAVA_OPTS`): `-Xmx512m -Xms256m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Duser.timezone=UTC -Dfile.encoding=UTF-8`. With the explicit `-Xmx512m` the heap stays at 512 MB inside the 1Gi limit, leaving room for metaspace, threads and buffers; if you raise the memory limit, raise `-Xmx` too, or drop it and let `MaxRAMPercentage` (75 percent of the container limit) size the heap. `UseContainerSupport` is available in Java 8u191 and later, which `eclipse-temurin:8-jdk-alpine` provides. Override with the `JAVA_OPTS` environment variable in the Deployment.
- **Shutdown**: the entrypoint is `sh -c "exec java ..."` so java is the first process and receives SIGTERM from Kubernetes.
- **Spring profiles**: `SPRING_PROFILES_ACTIVE` (default `default`); there are no profile-specific files in the project. Set another profile in `deployment.yaml` if you add `application-<profile>.properties`.
- **Health endpoints**: the application had none, so `spring-boot-starter-actuator` was added. `/actuator/health` is the overall status (includes Redis), `/actuator/health/liveness` and `/actuator/health/readiness` are enabled by `management.endpoint.health.probes.enabled=true` and are what the Kubernetes probes and the ALB use. Only `health` is exposed over HTTP. The endpoint runs on the application's port 8080 for as long as the process runs.
- **Packaging**: `spring-boot-maven-plugin` builds a runnable jar, started with `java -jar app.jar`.

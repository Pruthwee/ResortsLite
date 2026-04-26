# ResortsLite - Cloud-Ready Application

## Cloud Readiness Fixes Applied

This application has been transformed to be fully cloud-ready for Google Cloud Platform (GCP) deployment.

### Fixed Issues

#### 1. File System Dependencies (cr-java-0061, cr-java-0062, cr-java-0063)
- **Before**: Hard-coded file paths (`/var/legacy/reports/`, `C:\\ResortBackups\\`)
- **After**: Migrated to Google Cloud Storage (GCS)
- **Configuration**: Set `GCS_REPORTS_BUCKET` and `GCS_BACKUP_BUCKET` environment variables

#### 2. Hard-coded Database Credentials (cr-java-0069)
- **Before**: Credentials embedded in source code
- **After**: Externalized to environment variables with Google Secret Manager integration
- **Configuration**: Use Secret Manager for `DB_PASSWORD` and `REDIS_PASSWORD`

#### 3. Hard-coded Environment URLs (cr-java-0071)
- **Before**: Hard-coded HTTP URLs for services
- **After**: Externalized to environment variables with HTTPS
- **Configuration**: Set `PAYMENT_API_URL`, `INVENTORY_SERVICE_URL`, etc.

#### 4. Hard-coded Ports (cr-java-0077)
- **Before**: Fixed port 8080 in code
- **After**: Dynamic port binding using `PORT` environment variable
- **Configuration**: Cloud Run automatically sets `PORT`

#### 5. HTTP Session State Storage (cr-java-0065)
- **Before**: Session data stored in local memory
- **After**: Distributed session management using Redis (Memorystore)
- **Configuration**: Set `REDIS_HOST` and `REDIS_PORT`

#### 6. In-Memory Caching Without TTL (cr-java-0067)
- **Before**: Unbounded in-memory cache
- **After**: Redis-backed distributed cache with 30-minute TTL
- **Configuration**: Automatic with Redis configuration

#### 7. File-based Authentication (cr-java-0090)
- **Before**: Credentials stored in local files
- **After**: Google Secret Manager integration
- **Configuration**: Set `GCP_PROJECT_ID`

#### 8. Clock/Time Dependencies (cr-java-0111)
- **Before**: Server-local timezone
- **After**: All timestamps use UTC
- **Configuration**: Automatic

## GCP Services Required

1. **Google Cloud Storage**: For report and backup file storage
2. **Google Cloud Memorystore for Redis**: For distributed session and cache management
3. **Google Secret Manager**: For secure credential storage
4. **Cloud SQL** (optional): For production database
5. **Cloud Run or GKE**: For application deployment

## Environment Variables

### Required for Production

```bash
# Database
DB_URL=jdbc:postgresql://CLOUD_SQL_IP:5432/resortdb
DB_USERNAME=resort_user
DB_PASSWORD=<from-secret-manager>

# Redis (Memorystore)
REDIS_HOST=<memorystore-ip>
REDIS_PORT=6379
REDIS_PASSWORD=<from-secret-manager>

# GCS Buckets
GCS_REPORTS_BUCKET=resort-reports-prod
GCS_BACKUP_BUCKET=resort-backups-prod

# GCP Project
GCP_PROJECT_ID=your-project-id

# Service Endpoints
PAYMENT_API_URL=https://payment-api.example.com/charge
INVENTORY_SERVICE_URL=https://inventory.example.com/rooms
NOTIFICATION_SERVICE_URL=https://notify.example.com/send
REPORT_DOWNLOAD_URL=https://reports.example.com
```

## Local Development

For local development, the application uses sensible defaults:
- H2 in-memory database
- Local Redis (or embedded if not available)
- Local file system fallback for GCS operations

## Deployment Steps

### 1. Create GCP Resources

```bash
# Create GCS buckets
gsutil mb gs://resort-reports-prod
gsutil mb gs://resort-backups-prod

# Create Memorystore Redis instance
gcloud redis instances create resort-redis \
  --size=1 \
  --region=us-central1 \
  --redis-version=redis_6_x

# Create secrets in Secret Manager
echo -n "your-db-password" | gcloud secrets create db-password --data-file=-
echo -n "your-redis-password" | gcloud secrets create redis-password --data-file=-
```

### 2. Deploy to Cloud Run

```bash
# Build container image
gcloud builds submit --tag gcr.io/PROJECT_ID/resortslite

# Deploy to Cloud Run
gcloud run deploy resortslite \
  --image gcr.io/PROJECT_ID/resortslite \
  --platform managed \
  --region us-central1 \
  --set-env-vars GCP_PROJECT_ID=PROJECT_ID,REDIS_HOST=REDIS_IP \
  --set-secrets DB_PASSWORD=db-password:latest,REDIS_PASSWORD=redis-password:latest
```

### 3. Deploy to GKE

```bash
# Create Kubernetes deployment with ConfigMap and Secrets
kubectl create configmap resortslite-config \
  --from-literal=GCP_PROJECT_ID=PROJECT_ID \
  --from-literal=REDIS_HOST=REDIS_IP

kubectl create secret generic resortslite-secrets \
  --from-literal=DB_PASSWORD=your-password \
  --from-literal=REDIS_PASSWORD=your-redis-password

kubectl apply -f k8s/deployment.yaml
```

## Testing Cloud Readiness

### 1. Test GCS Integration
```bash
curl -X POST http://localhost:8080/api/bookings/report/download?month=2024-03
```

### 2. Test Redis Session Management
```bash
# Create booking
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=SUITE&checkIn=2024-03-01&checkOut=2024-03-05"

# Verify session persists across instances
curl http://localhost:8080/api/bookings/status/BK-XXXXX
```

### 3. Test Environment Variable Configuration
```bash
# Verify all hard-coded values are externalized
curl http://localhost:8080/actuator/health
```

## Security Considerations

1. **Never commit secrets**: Use Secret Manager for all sensitive data
2. **Use HTTPS**: All service endpoints use HTTPS in production
3. **Enable Cloud IAM**: Use service accounts with minimal permissions
4. **Rotate credentials**: Regularly rotate database and Redis passwords
5. **Enable audit logging**: Use Cloud Logging for security monitoring

## Monitoring and Observability

- **Health checks**: Available at `/actuator/health`
- **Metrics**: Available at `/actuator/metrics`
- **Logging**: Structured logs sent to Cloud Logging
- **Tracing**: Compatible with Cloud Trace

## Support

For issues or questions about cloud deployment, refer to:
- [Google Cloud Documentation](https://cloud.google.com/docs)
- [Spring Cloud GCP](https://spring.io/projects/spring-cloud-gcp)

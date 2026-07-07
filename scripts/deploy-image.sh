#!/bin/bash
set -e
set -o pipefail

# ============================================================
# deploy-image.sh — Deploy ResortsLite to AWS EKS
# ============================================================

APP_NAME="resortsLite"
NAMESPACE="resortsLite"
MANIFESTS_DIR="kubernetes"

echo "============================================"
echo "  ResortsLite — AWS EKS Deployment"
echo "============================================"
echo ""

# ---- AWS / EKS configuration ----
read -rp "Enter AWS region (e.g. us-east-1): " AWS_REGION
if [ -z "$AWS_REGION" ]; then
  echo "ERROR: AWS region is required." >&2
  exit 1
fi

read -rp "Enter EKS cluster name: " CLUSTER_NAME
if [ -z "$CLUSTER_NAME" ]; then
  echo "ERROR: EKS cluster name is required." >&2
  exit 1
fi

read -rp "Enter full Docker image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortsLite:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
  echo "ERROR: Docker image URI is required." >&2
  exit 1
fi

echo ""
echo "---- Application environment variables ----"
echo "(Press Enter to keep the default placeholder value)"
echo ""

read -rp "Enter REDIS_HOST (default: localhost): " INPUT_REDIS_HOST
REDIS_HOST="${INPUT_REDIS_HOST:-localhost}"

read -rp "Enter REDIS_PORT (default: 6379): " INPUT_REDIS_PORT
REDIS_PORT="${INPUT_REDIS_PORT:-6379}"

read -rp "Enter S3_BUCKET_NAME (default: resorts-reports-bucket): " INPUT_S3_BUCKET
S3_BUCKET_NAME="${INPUT_S3_BUCKET:-resorts-reports-bucket}"

read -rp "Enter S3_BACKUP_PREFIX (default: backups/nightly/): " INPUT_S3_PREFIX
S3_BACKUP_PREFIX="${INPUT_S3_PREFIX:-backups/nightly/}"

read -rp "Enter PAYMENT_API_ENDPOINT (default: http://payment-service/payments/charge): " INPUT_PAYMENT
PAYMENT_API_ENDPOINT="${INPUT_PAYMENT:-http://payment-service/payments/charge}"

echo ""
echo "Configuring kubectl for EKS cluster: $CLUSTER_NAME in $AWS_REGION ..."
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "ERROR: Cannot connect to EKS cluster." >&2; exit 1; }

# ---- Substitute placeholders in manifests ----
echo ""
echo "Updating Kubernetes manifests with deployment values..."

# Work on copies to avoid modifying originals
cp "${MANIFESTS_DIR}/deployment.yaml" "${MANIFESTS_DIR}/deployment.yaml.bak"

sed -i 's|{{IMAGE_URI}}|'"$IMAGE_URI"'|g'                         "${MANIFESTS_DIR}/deployment.yaml"
sed -i 's|{{REDIS_HOST}}|'"$REDIS_HOST"'|g'                       "${MANIFESTS_DIR}/deployment.yaml"
sed -i 's|{{REDIS_PORT}}|'"$REDIS_PORT"'|g'                       "${MANIFESTS_DIR}/deployment.yaml"
sed -i 's|{{S3_BUCKET_NAME}}|'"$S3_BUCKET_NAME"'|g'               "${MANIFESTS_DIR}/deployment.yaml"
sed -i 's|{{S3_BACKUP_PREFIX}}|'"$S3_BACKUP_PREFIX"'|g'           "${MANIFESTS_DIR}/deployment.yaml"
sed -i 's|{{PAYMENT_API_ENDPOINT}}|'"$PAYMENT_API_ENDPOINT"'|g'   "${MANIFESTS_DIR}/deployment.yaml"

# ---- Apply manifests ----
echo ""
echo "Applying Kubernetes manifests..."

echo "  [1/4] Applying namespace..."
kubectl apply -f "${MANIFESTS_DIR}/namespace.yaml"

echo "  [2/4] Applying deployment..."
kubectl apply -f "${MANIFESTS_DIR}/deployment.yaml"

echo "  [3/4] Applying service..."
kubectl apply -f "${MANIFESTS_DIR}/service.yaml"

echo "  [4/4] Applying ingress..."
kubectl apply -f "${MANIFESTS_DIR}/ingress.yaml"

# ---- Restore original deployment.yaml ----
mv "${MANIFESTS_DIR}/deployment.yaml.bak" "${MANIFESTS_DIR}/deployment.yaml"

# ---- Wait for rollout ----
echo ""
echo "Waiting for deployment rollout..."
kubectl rollout status deployment/"$APP_NAME" -n "$NAMESPACE" --timeout=300s

# ---- Verify resources ----
echo ""
echo "Verifying deployed resources..."
kubectl get pods,svc,ingress -n "$NAMESPACE"

# ---- Display access URL ----
echo ""
INGRESS_HOST=$(kubectl get ingress "${APP_NAME}-ingress" -n "$NAMESPACE" \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || echo "<pending>")

echo "============================================"
echo "  Deployment complete!"
echo "  Application URL: http://${INGRESS_HOST}"
echo "  Health check   : http://${INGRESS_HOST}/actuator/health"
echo "============================================"
echo ""
echo "Rollback command (if needed):"
echo "  kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE"

#!/bin/bash
# Deploys the pushed ResortsLite image to an AWS EKS cluster with kubectl.
# Run from the repository root: ./scripts/deploy-image.sh
set -e
set -o pipefail

APP_NAME="resortslite"
NAMESPACE="resortslite"

echo "=== Deploy ${APP_NAME} to AWS EKS ==="

for tool in aws kubectl sed; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "ERROR: $tool is not installed or not in PATH"
    exit 1
  fi
done

read -r -p "Enter the AWS region (for example us-east-1): " AWS_REGION
read -r -p "Enter the EKS cluster name: " CLUSTER_NAME
read -r -p "Enter the image URI (full path with registry and tag): " IMAGE_URI

if [ -z "$AWS_REGION" ] || [ -z "$CLUSTER_NAME" ] || [ -z "$IMAGE_URI" ]; then
  echo "ERROR: the region, the cluster name and the image URI are required"
  exit 1
fi

# Environment variables the application needs
read -r -p "Enter value for REDIS_HOST (or press Enter to skip): " REDIS_HOST
read -r -p "Enter value for REDIS_PORT (or press Enter to skip): " REDIS_PORT
read -r -p "Enter value for REDIS_SSL (or press Enter to skip): " REDIS_SSL
read -r -p "Enter value for PAYMENT_API_URL (or press Enter to skip): " PAYMENT_API_URL

if [ -z "$REDIS_HOST" ]; then
  echo "ERROR: REDIS_HOST is required, the HTTP sessions and the booking cache are stored in Redis"
  exit 1
fi
REDIS_PORT=${REDIS_PORT:-6379}
REDIS_SSL=${REDIS_SSL:-false}

# Credential, read without echo
read -r -s -p "Enter value for REDIS_PASSWORD (or press Enter to skip): " REDIS_PASSWORD
echo ""

echo "Configuring kubectl for the cluster ${CLUSTER_NAME} ..."
aws eks update-kubeconfig --region $AWS_REGION --name $CLUSTER_NAME || { echo "ERROR: could not configure kubectl for the cluster"; exit 1; }
kubectl cluster-info || exit 1

cleanup() {
  rm -f kubernetes/deployment.yaml.bak
  if [ -f "$JOB_BACKUP" ]; then
    cp "$JOB_BACKUP" kubernetes/deployment.yaml
    rm -f "$JOB_BACKUP"
  fi
}
JOB_BACKUP=$(mktemp)
cp kubernetes/deployment.yaml "$JOB_BACKUP"
trap cleanup EXIT

echo "Replacing the placeholders in kubernetes/deployment.yaml ..."
sed -i.bak 's|{{IMAGE_URI}}|'"$IMAGE_URI"'|g' kubernetes/deployment.yaml && rm -f kubernetes/deployment.yaml.bak
sed -i.bak 's|{{NAMESPACE}}|'"$NAMESPACE"'|g' kubernetes/deployment.yaml && rm -f kubernetes/deployment.yaml.bak
sed -i.bak 's|{{REDIS_HOST}}|'"$REDIS_HOST"'|g' kubernetes/deployment.yaml && rm -f kubernetes/deployment.yaml.bak
sed -i.bak 's|{{REDIS_PORT}}|'"$REDIS_PORT"'|g' kubernetes/deployment.yaml && rm -f kubernetes/deployment.yaml.bak
sed -i.bak 's|{{REDIS_SSL}}|'"$REDIS_SSL"'|g' kubernetes/deployment.yaml && rm -f kubernetes/deployment.yaml.bak
sed -i.bak 's|{{PAYMENT_API_URL}}|'"$PAYMENT_API_URL"'|g' kubernetes/deployment.yaml && rm -f kubernetes/deployment.yaml.bak

echo "Applying kubernetes/namespace.yaml ..."
kubectl apply -f kubernetes/namespace.yaml || { echo "ERROR: namespace.yaml failed"; exit 1; }

echo "Creating the Secret ${APP_NAME}-secrets ..."
kubectl create secret generic ${APP_NAME}-secrets -n ${NAMESPACE} --from-literal=REDIS_PASSWORD="$REDIS_PASSWORD" --dry-run=client -o yaml | kubectl apply -f - || { echo "ERROR: the Secret could not be created"; exit 1; }

echo "Applying kubernetes/deployment.yaml ..."
kubectl apply -f kubernetes/deployment.yaml || { echo "ERROR: deployment.yaml failed"; exit 1; }
echo "Applying kubernetes/service.yaml ..."
kubectl apply -f kubernetes/service.yaml || { echo "ERROR: service.yaml failed"; exit 1; }
echo "Applying kubernetes/ingress.yaml ..."
kubectl apply -f kubernetes/ingress.yaml || { echo "ERROR: ingress.yaml failed"; exit 1; }

echo "Waiting for the rollout ..."
if ! kubectl rollout status deployment/${APP_NAME} -n ${NAMESPACE} --timeout=300s; then
  echo "ERROR: the rollout failed"
  echo "Rollback with: kubectl rollout undo deployment/${APP_NAME} -n ${NAMESPACE}"
  echo "Inspect with:  kubectl describe pods -n ${NAMESPACE}  and  kubectl logs deployment/${APP_NAME} -n ${NAMESPACE}"
  exit 1
fi

kubectl get pods,svc,ingress -n ${NAMESPACE}

INGRESS_HOST=$(kubectl get ingress ${APP_NAME}-ingress -n ${NAMESPACE} -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)
if [ -n "$INGRESS_HOST" ]; then
  echo "Application URL: http://${INGRESS_HOST}"
else
  echo "The load balancer address is not ready yet. Check later with: kubectl get ingress -n ${NAMESPACE}"
fi
echo "Health check: /actuator/health"
echo "Deployment finished."

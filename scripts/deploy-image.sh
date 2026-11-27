#!/bin/bash
# Deploy the pushed image to AWS EKS. Run from the repository root: ./scripts/deploy-image.sh
set -e
set -o pipefail

APP_NAME="resortlite-mono2"
NAMESPACE="resortlite-mono2"

command -v aws >/dev/null 2>&1 || { echo "ERROR: the AWS CLI is not installed or not in PATH"; exit 1; }
command -v kubectl >/dev/null 2>&1 || { echo "ERROR: kubectl is not installed or not in PATH"; exit 1; }

echo "=== Deploying $APP_NAME to AWS EKS ==="
read -r -p "Enter the AWS region (for example us-east-1): " AWS_REGION
read -r -p "Enter the EKS cluster name: " CLUSTER_NAME
read -r -p "Enter the image URI (registry/repository:tag): " IMAGE_URI

if [ -z "$AWS_REGION" ] || [ -z "$CLUSTER_NAME" ] || [ -z "$IMAGE_URI" ]; then
  echo "ERROR: region, cluster name and image URI are required"
  exit 1
fi

read -r -p "Enter value for PAYMENT_API_URL (or press Enter to skip): " PAYMENT_API_URL
PAYMENT_API_URL=${PAYMENT_API_URL:-http://payment-service:9090/payments/charge}
read -r -p "Enter value for REDIS_HOST, the ElastiCache endpoint (or press Enter to skip): " REDIS_HOST
read -r -p "Enter value for REDIS_PORT (or press Enter to skip): " REDIS_PORT
REDIS_PORT=${REDIS_PORT:-6379}
read -r -p "Enter value for REDIS_SSL, true or false (or press Enter to skip): " REDIS_SSL
REDIS_SSL=${REDIS_SSL:-false}
read -r -s -p "Enter value for REDIS_PASSWORD, the Redis AUTH token (input hidden, or press Enter to skip): " REDIS_PASSWORD
echo

if [ -z "$REDIS_HOST" ]; then
  echo "WARNING: REDIS_HOST is empty; sessions and the booking cache need Redis, so requests that use them will fail."
fi

# Keep the manifest template intact: the placeholders are replaced in the file only while deploying.
TEMPLATE_BACKUP=$(mktemp)
cp kubernetes/deployment.yaml "$TEMPLATE_BACKUP"
restore_template() { cp "$TEMPLATE_BACKUP" kubernetes/deployment.yaml; rm -f "$TEMPLATE_BACKUP" kubernetes/deployment.yaml.bak; }
trap restore_template EXIT

echo "Configuring kubectl for cluster $CLUSTER_NAME..."
if ! aws eks update-kubeconfig --region $AWS_REGION --name $CLUSTER_NAME; then
  echo "ERROR: could not configure kubectl; check the region, the cluster name and your AWS credentials"
  exit 1
fi
kubectl cluster-info || exit 1

echo "Applying the namespace..."
kubectl apply -f kubernetes/namespace.yaml || { echo "ERROR: namespace apply failed"; exit 1; }

echo "Creating the Secret $APP_NAME-secrets..."
kubectl create secret generic $APP_NAME-secrets -n $NAMESPACE --from-literal=REDIS_PASSWORD="$REDIS_PASSWORD" --dry-run=client -o yaml | kubectl apply -f - || { echo "ERROR: secret creation failed"; exit 1; }

echo "Filling in the placeholders..."
sed -i.bak 's|{{IMAGE_URI}}|'"$IMAGE_URI"'|g' kubernetes/deployment.yaml
sed -i.bak 's|{{NAMESPACE}}|'"$NAMESPACE"'|g' kubernetes/deployment.yaml
sed -i.bak 's|{{PAYMENT_API_URL}}|'"$PAYMENT_API_URL"'|g' kubernetes/deployment.yaml
sed -i.bak 's|{{REDIS_HOST}}|'"$REDIS_HOST"'|g' kubernetes/deployment.yaml
sed -i.bak 's|{{REDIS_PORT}}|'"$REDIS_PORT"'|g' kubernetes/deployment.yaml
sed -i.bak 's|{{REDIS_SSL}}|'"$REDIS_SSL"'|g' kubernetes/deployment.yaml
rm -f kubernetes/deployment.yaml.bak

echo "Applying deployment, service and ingress..."
kubectl apply -f kubernetes/deployment.yaml || { echo "ERROR: deployment apply failed"; exit 1; }
kubectl apply -f kubernetes/service.yaml || { echo "ERROR: service apply failed"; exit 1; }
kubectl apply -f kubernetes/ingress.yaml || { echo "ERROR: ingress apply failed"; exit 1; }

echo "Waiting for the rollout..."
if ! kubectl rollout status deployment/$APP_NAME -n $NAMESPACE --timeout=300s; then
  echo "ERROR: the rollout did not finish."
  echo "Inspect:  kubectl describe pods -n $NAMESPACE ; kubectl logs deployment/$APP_NAME -n $NAMESPACE"
  echo "Roll back: kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE"
  exit 1
fi

kubectl get pods,svc,ingress -n $NAMESPACE

INGRESS_HOST=$(kubectl get ingress $APP_NAME-ingress -n $NAMESPACE -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)
if [ -n "$INGRESS_HOST" ]; then
  echo "Application URL: http://$INGRESS_HOST (host rule: $APP_NAME.example.com, send that Host header or point DNS to the load balancer)"
else
  echo "The load balancer address is not ready yet. Check later with: kubectl get ingress -n $NAMESPACE"
fi

echo "Rollback instructions if something goes wrong:"
echo "  kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE"
echo "  kubectl rollout status deployment/$APP_NAME -n $NAMESPACE"
echo "Done."

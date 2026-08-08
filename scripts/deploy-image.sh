#!/bin/bash
set -e
set -o pipefail

NAMESPACE="restorelitecontainer"
APP_NAME="restorelitecontainer"

read -r -p "Enter Azure resource group: " RESOURCE_GROUP
read -r -p "Enter AKS cluster name: " CLUSTER_NAME
read -r -p "Enter Docker image URI: " IMAGE_URI

if [ -z "$RESOURCE_GROUP" ] || [ -z "$CLUSTER_NAME" ] || [ -z "$IMAGE_URI" ]; then
  echo "Resource group, cluster name, and image URI are required."
  exit 1
fi

read -r -p "Enter value for PAYMENT_API_URL (or press Enter to skip): " PAYMENT_API_URL
read -r -p "Enter value for INVENTORY_API_URL (or press Enter to skip): " INVENTORY_API_URL
read -r -p "Enter value for INVENTORY_SERVICE_PORT (or press Enter to skip): " INVENTORY_SERVICE_PORT
read -r -p "Enter value for REPORT_BASE_PATH (or press Enter to skip): " REPORT_BASE_PATH
read -r -p "Enter value for BACKUP_PATH (or press Enter to skip): " BACKUP_PATH

PAYMENT_API_URL=${PAYMENT_API_URL:-http://payment-service:9090/payments/charge}
INVENTORY_API_URL=${INVENTORY_API_URL:-https://inventory-service.internal:8081/rooms/available}
INVENTORY_SERVICE_PORT=${INVENTORY_SERVICE_PORT:-8081}
REPORT_BASE_PATH=${REPORT_BASE_PATH:-/tmp/reports}
BACKUP_PATH=${BACKUP_PATH:-/tmp/backups}

az aks get-credentials --resource-group "$RESOURCE_GROUP" --name "$CLUSTER_NAME" --overwrite-existing
kubectl cluster-info

TMP_DEPLOYMENT=$(mktemp)
cp kubernetes/deployment.yaml "$TMP_DEPLOYMENT"
sed -i 's|{{IMAGE_URI}}|'"$IMAGE_URI"'|g' "$TMP_DEPLOYMENT"
sed -i 's|{{PAYMENT_API_URL}}|'"$PAYMENT_API_URL"'|g' "$TMP_DEPLOYMENT"
sed -i 's|{{INVENTORY_API_URL}}|'"$INVENTORY_API_URL"'|g' "$TMP_DEPLOYMENT"
sed -i 's|{{INVENTORY_SERVICE_PORT}}|'"$INVENTORY_SERVICE_PORT"'|g' "$TMP_DEPLOYMENT"
sed -i 's|{{REPORT_BASE_PATH}}|'"$REPORT_BASE_PATH"'|g' "$TMP_DEPLOYMENT"
sed -i 's|{{BACKUP_PATH}}|'"$BACKUP_PATH"'|g' "$TMP_DEPLOYMENT"

kubectl apply -f kubernetes/namespace.yaml
kubectl apply -f "$TMP_DEPLOYMENT"
kubectl apply -f kubernetes/service.yaml
kubectl apply -f kubernetes/ingress.yaml

kubectl rollout status deployment/$APP_NAME -n $NAMESPACE
kubectl get pods,svc,ingress -n $NAMESPACE

echo "Application should be available via host: restorelitecontainer.example.com"
echo "Rollback command: kubectl rollout undo deployment/$APP_NAME -n $NAMESPACE"
rm -f "$TMP_DEPLOYMENT"

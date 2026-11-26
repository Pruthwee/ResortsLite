#!/bin/bash
set -e
set -o pipefail

# Deploy script for Resortslite_Mono on AWS EKS

echo "=== Resortslite EKS Deployment ==="

PROJECT_NAME="ResortsLite"
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')
NAMESPACE="resortslite"

# Ask for AWS region
read -p "Enter AWS region (e.g., us-east-1): " AWS_REGION

# Ask for EKS cluster name
read -p "Enter EKS cluster name: " CLUSTER_NAME

# Ask for image URI
read -p "Enter full image URI (e.g., 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): " IMAGE_URI

# Ask for environment variables
echo ""
echo "Enter values for environment variables (press Enter to skip):"
read -p "Enter value for PAYMENT_API_ENDPOINT (or press Enter to skip): " PAYMENT_API_ENDPOINT

echo ""
echo "=== Configuring kubectl for EKS cluster ==="
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"

echo ""
echo "=== Checking cluster connectivity ==="
kubectl cluster-info || exit 1

echo ""
echo "=== Creating namespace ==="
kubectl apply -f kubernetes/namespace.yaml

echo ""
echo "=== Creating secrets ==="
# Redis credentials
read -s -p "Enter Redis host (or press Enter to skip): " REDIS_HOST
echo ""
read -s -p "Enter Redis port (or press Enter to skip): " REDIS_PORT
echo ""
read -s -p "Enter Redis password (or press Enter to skip): " REDIS_PASSWORD
echo ""

if [ -n "$REDIS_HOST" ] || [ -n "$REDIS_PORT" ] || [ -n "$REDIS_PASSWORD" ]; then
    SECRET_ARGS=""
    if [ -n "$REDIS_HOST" ]; then
        SECRET_ARGS="$SECRET_ARGS --from-literal=redis-host=$REDIS_HOST"
    fi
    if [ -n "$REDIS_PORT" ]; then
        SECRET_ARGS="$SECRET_ARGS --from-literal=redis-port=$REDIS_PORT"
    fi
    if [ -n "$REDIS_PASSWORD" ]; then
        SECRET_ARGS="$SECRET_ARGS --from-literal=redis-password=$REDIS_PASSWORD"
    fi
    kubectl create secret generic resortslite-secrets -n "$NAMESPACE" $SECRET_ARGS --dry-run=client -o yaml | kubectl apply -f -
fi

echo ""
echo "=== Replacing placeholders and deploying ==="
# Replace placeholders in deployment.yaml
sed -i.bak "s|{{IMAGE_URI}}|$IMAGE_URI|g" kubernetes/deployment.yaml

if [ -n "$PAYMENT_API_ENDPOINT" ]; then
    sed -i.bak "s|{{PAYMENT_API_ENDPOINT}}|$PAYMENT_API_ENDPOINT|g" kubernetes/deployment.yaml
else
    sed -i.bak "s|{{PAYMENT_API_ENDPOINT}}|http://payment-svc:9090/payments/charge|g" kubernetes/deployment.yaml
fi

# Remove backup files
rm -f kubernetes/deployment.yaml.bak

echo ""
echo "=== Applying deployment ==="
kubectl apply -f kubernetes/deployment.yaml

echo ""
echo "=== Applying service ==="
kubectl apply -f kubernetes/service.yaml

echo ""
echo "=== Applying ingress ==="
kubectl apply -f kubernetes/ingress.yaml

echo ""
echo "=== Waiting for deployment rollout ==="
kubectl rollout status deployment/resortslite -n "$NAMESPACE"

echo ""
echo "=== Current pods and services ==="
kubectl get pods,svc,ingress -n "$NAMESPACE"

echo ""
echo "=== Deployment complete ==="
echo "Application URL: https://resortslite.example.com"
echo ""
echo "=== Rollback instructions ==="
echo "If the deployment fails, run: kubectl rollout undo deployment/resortslite -n $NAMESPACE"
echo "To check pod logs: kubectl logs -l app=resortslite -n $NAMESPACE --tail=100"
echo "To delete the deployment: kubectl delete -f kubernetes/ -n $NAMESPACE"

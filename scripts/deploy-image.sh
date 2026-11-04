#!/bin/bash
set -e
set -o pipefail

PROJECT_NAME="ResortsLite"
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

echo "=== Deploy $IMAGE_NAME to AWS EKS ==="

# Ask for cluster details
read -p "Enter AWS region: " AWS_REGION
read -p "Enter EKS cluster name: " CLUSTER_NAME
read -p "Enter full image URI (registry/repo:tag): " IMAGE_URI

# Ask for environment variables
echo ""
echo "Enter values for environment variables (press Enter to skip):"

read -p "Enter value for REDIS_HOST (or press Enter to skip): " REDIS_HOST
read -p "Enter value for REDIS_PORT (or press Enter to skip): " REDIS_PORT
read -p "Enter value for REDIS_PASSWORD (or press Enter to skip): " REDIS_PASSWORD
read -p "Enter value for PAYMENT_API_URL (or press Enter to skip): " PAYMENT_API_URL
read -p "Enter value for DB_USERNAME (or press Enter to skip): " DB_USERNAME
read -s -p "Enter value for DB_PASSWORD (or press Enter to skip): " DB_PASSWORD
echo ""

# Configure kubectl for EKS
echo "Configuring kubectl for EKS cluster..."
aws eks update-kubeconfig --region "$AWS_REGION" --name "$CLUSTER_NAME"
kubectl cluster-info || exit 1

# Apply namespace
echo "Applying namespace..."
kubectl apply -f kubernetes/namespace.yaml

# Create Secret for credentials
echo "Creating Kubernetes Secret..."
SECRET_ARGS=""
if [ -n "$REDIS_PASSWORD" ]; then
    SECRET_ARGS="$SECRET_ARGS --from-literal=redis-password=\"$REDIS_PASSWORD\""
fi
if [ -n "$DB_USERNAME" ]; then
    SECRET_ARGS="$SECRET_ARGS --from-literal=db-username=\"$DB_USERNAME\""
fi
if [ -n "$DB_PASSWORD" ]; then
    SECRET_ARGS="$SECRET_ARGS --from-literal=db-password=\"$DB_PASSWORD\""
fi
if [ -n "$REDIS_HOST" ]; then
    SECRET_ARGS="$SECRET_ARGS --from-literal=redis-host=\"$REDIS_HOST\""
fi
if [ -n "$REDIS_PORT" ]; then
    SECRET_ARGS="$SECRET_ARGS --from-literal=redis-port=\"$REDIS_PORT\""
fi

if [ -n "$SECRET_ARGS" ]; then
    kubectl create secret generic "$IMAGE_NAME-secrets" -n "$IMAGE_NAME" $SECRET_ARGS --dry-run=client -o yaml | kubectl apply -f -
fi

# Replace placeholders in deployment.yaml
echo "Deploying application..."

# Create a temporary copy of deployment.yaml with placeholders replaced
cp kubernetes/deployment.yaml /tmp/deployment.yaml.bak

sed -i.bak "s|{{IMAGE_URI}}|$IMAGE_URI|g" kubernetes/deployment.yaml
sed -i.bak "s|{{NAMESPACE}}|$IMAGE_NAME|g" kubernetes/deployment.yaml

if [ -n "$REDIS_HOST" ]; then
    sed -i.bak "s|{{REDIS_HOST}}|$REDIS_HOST|g" kubernetes/deployment.yaml
fi
if [ -n "$REDIS_PORT" ]; then
    sed -i.bak "s|{{REDIS_PORT}}|$REDIS_PORT|g" kubernetes/deployment.yaml
fi
if [ -n "$REDIS_PASSWORD" ]; then
    sed -i.bak "s|{{REDIS_PASSWORD}}|$REDIS_PASSWORD|g" kubernetes/deployment.yaml
fi
if [ -n "$PAYMENT_API_URL" ]; then
    sed -i.bak "s|{{PAYMENT_API_URL}}|$PAYMENT_API_URL|g" kubernetes/deployment.yaml
fi
if [ -n "$DB_USERNAME" ]; then
    sed -i.bak "s|{{DB_USERNAME}}|$DB_USERNAME|g" kubernetes/deployment.yaml
fi
if [ -n "$DB_PASSWORD" ]; then
    sed -i.bak "s|{{DB_PASSWORD}}|$DB_PASSWORD|g" kubernetes/deployment.yaml
fi

# Remove backup files
rm -f kubernetes/deployment.yaml.bak

# Apply deployment, service, and ingress
kubectl apply -f kubernetes/deployment.yaml
kubectl apply -f kubernetes/service.yaml
kubectl apply -f kubernetes/ingress.yaml

# Wait for rollout
echo "Waiting for deployment rollout..."
kubectl rollout status deployment/"$IMAGE_NAME" -n "$IMAGE_NAME"

# Show status
echo "=== Deployment Status ==="
kubectl get pods,svc,ingress -n "$IMAGE_NAME"

# Show application URL
echo ""
echo "=== Application URL ==="
echo "http://resortslite.example.com"

echo ""
echo "=== Deployment Complete ==="
echo "To rollback: kubectl rollout undo deployment/$IMAGE_NAME -n $IMAGE_NAME"

#!/bin/bash
set -e
set -o pipefail

PROJECT_NAME="resortslite"

echo "-------------------------------------------------------"
echo "Deploying $PROJECT_NAME to AWS EKS"
echo "-------------------------------------------------------"

# Prompt for AWS and EKS details
read -p "Enter AWS Region [us-east-1]: " AWS_REGION
AWS_REGION=${AWS_REGION:-us-east-1}
read -p "Enter EKS Cluster Name: " CLUSTER_NAME
if [ -z "$CLUSTER_NAME" ]; then
    echo "Error: Cluster name is required."
    exit 1
fi

# Prompt for Docker image URI
read -p "Enter full Docker image URI (e.g., 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
    echo "Error: Image URI is required."
    exit 1
fi

# Prompt for application-specific environment variables
echo "Enter values for the following environment variables (or press Enter to skip):"
read -p "DB_URL [jdbc:h2:mem:resortdb;DB_CLOSE_DELAY=-1]: " DB_URL
DB_URL=${DB_URL:-jdbc:h2:mem:resortdb;DB_CLOSE_DELAY=-1}
read -p "DB_USERNAME [sa]: " DB_USERNAME
DB_USERNAME=${DB_USERNAME:-sa}
read -p "DB_PASSWORD: " DB_PASSWORD
read -p "PAYMENT_ENDPOINT [http://payment-svc.internal:9090/charge]: " PAYMENT_ENDPOINT
PAYMENT_ENDPOINT=${PAYMENT_ENDPOINT:-http://payment-svc.internal:9090/charge}
read -p "INVENTORY_ENDPOINT [http://inventory-svc.internal:8081/rooms]: " INVENTORY_ENDPOINT
INVENTORY_ENDPOINT=${INVENTORY_ENDPOINT:-http://inventory-svc.internal:8081/rooms}
read -p "NOTIFICATION_ENDPOINT [http://notify.internal:7070/send]: " NOTIFICATION_ENDPOINT
NOTIFICATION_ENDPOINT=${NOTIFICATION_ENDPOINT:-http://notify.internal:7070/send}

# Update Kubernetes manifests
echo "Updating manifests with provided values..."
sed -i "s|{{IMAGE_URI}}|$IMAGE_URI|g" kubernetes/deployment.yaml
sed -i "s|{{DB_URL}}|$DB_URL|g" kubernetes/deployment.yaml
sed -i "s|{{DB_USERNAME}}|$DB_USERNAME|g" kubernetes/deployment.yaml
sed -i "s|{{DB_PASSWORD}}|$DB_PASSWORD|g" kubernetes/deployment.yaml
sed -i "s|{{PAYMENT_ENDPOINT}}|$PAYMENT_ENDPOINT|g" kubernetes/deployment.yaml
sed -i "s|{{INVENTORY_ENDPOINT}}|$INVENTORY_ENDPOINT|g" kubernetes/deployment.yaml
sed -i "s|{{NOTIFICATION_ENDPOINT}}|$NOTIFICATION_ENDPOINT|g" kubernetes/deployment.yaml

# Configure kubectl
echo "Configuring kubectl for EKS cluster..."
aws eks update-kubeconfig --region $AWS_REGION --name $CLUSTER_NAME

# Verify connectivity
echo "Verifying cluster connectivity..."
kubectl cluster-info || { echo "Error: Could not connect to EKS cluster"; exit 1; }

# Apply manifests in order
echo "Applying Kubernetes manifests..."
kubectl apply -f kubernetes/namespace.yaml
kubectl apply -f kubernetes/deployment.yaml
kubectl apply -f kubernetes/service.yaml
kubectl apply -f kubernetes/ingress.yaml

# Wait for rollout
echo "Waiting for deployment rollout..."
kubectl rollout status deployment/$PROJECT_NAME -n $PROJECT_NAME

# Verify resources
echo "Verifying deployed resources..."
kubectl get pods,svc,ingress -n $PROJECT_NAME

echo "-------------------------------------------------------"
echo "Deployment successful!"
echo "Application is accessible via the Ingress host defined in ingress.yaml"
echo "-------------------------------------------------------"
echo "Rollback instructions: kubectl rollout undo deployment/$PROJECT_NAME -n $PROJECT_NAME"

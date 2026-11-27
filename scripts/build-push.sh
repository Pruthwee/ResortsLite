#!/bin/bash
# Build the Docker image and push it to AWS ECR or Docker Hub.
# Run from the repository root: ./scripts/build-push.sh
set -e
set -o pipefail

PROJECT_NAME="Resortlite_Mono2"
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

command -v docker >/dev/null 2>&1 || { echo "ERROR: docker is not installed or not in PATH"; exit 1; }

echo "=== Build and push: $IMAGE_NAME ==="
echo "Select the registry:"
echo "  1. AWS ECR"
echo "  2. Docker Hub"
read -r -p "Enter 1 or 2: " REGISTRY_CHOICE

if [ "$REGISTRY_CHOICE" = "1" ]; then
  command -v aws >/dev/null 2>&1 || { echo "ERROR: the AWS CLI is not installed or not in PATH"; exit 1; }
  read -r -p "Enter the AWS region (for example us-east-1): " AWS_REGION
  read -r -p "Enter the AWS account ID: " AWS_ACCOUNT_ID
  read -r -p "Enter the ECR repository name [$IMAGE_NAME]: " ECR_REPO
  ECR_REPO=${ECR_REPO:-$IMAGE_NAME}
  ECR_REPO=$(echo "$ECR_REPO" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9/-' '-' | sed 's/^-*//;s/-*$//')
  REGISTRY_URL="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
  REPO_PATH="$ECR_REPO"
elif [ "$REGISTRY_CHOICE" = "2" ]; then
  read -r -p "Enter the Docker Hub username: " DOCKER_USERNAME
  read -r -s -p "Enter the Docker Hub password or access token: " DOCKER_PASSWORD
  echo
  read -r -p "Enter the repository name [$IMAGE_NAME]: " DOCKER_REPO
  DOCKER_REPO=${DOCKER_REPO:-$IMAGE_NAME}
  DOCKER_REPO=$(echo "$DOCKER_REPO" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')
  REGISTRY_URL="docker.io"
  REPO_PATH="${DOCKER_USERNAME}/${DOCKER_REPO}"
else
  echo "ERROR: invalid choice '$REGISTRY_CHOICE', enter 1 or 2"
  exit 1
fi

read -r -p "Enter the image tag [latest]: " IMAGE_TAG
IMAGE_TAG=$(echo "$IMAGE_TAG" | tr -cs 'A-Za-z0-9_.-' '-' | sed 's/^-*//;s/-*$//')
IMAGE_TAG=${IMAGE_TAG:-latest}

FULL_IMAGE_NAME="${REGISTRY_URL}/${REPO_PATH}:${IMAGE_TAG}"
echo "Image to build: $FULL_IMAGE_NAME"

echo "Step 1/3: logging in to the registry..."
if [ "$REGISTRY_CHOICE" = "1" ]; then
  aws ecr get-login-password --region $AWS_REGION | docker login --username AWS --password-stdin $REGISTRY_URL
  echo "Checking the ECR repository..."
  aws ecr describe-repositories --repository-names $ECR_REPO --region $AWS_REGION >/dev/null 2>&1 || aws ecr create-repository --repository-name $ECR_REPO --region $AWS_REGION
else
  echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin
fi

echo "Step 2/3: building the image..."
docker build -f Dockerfile -t "$FULL_IMAGE_NAME" .

echo "Step 3/3: pushing the image..."
docker push "$FULL_IMAGE_NAME"

echo "Done. Image pushed: $FULL_IMAGE_NAME"
echo "Deploy it with: ./scripts/deploy-image.sh (use this image URI)"

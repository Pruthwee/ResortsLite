#!/bin/bash
# Builds the ResortsLite image and pushes it to AWS ECR or Docker Hub.
# Run from the repository root: ./scripts/build-push.sh
set -e
set -o pipefail

PROJECT_NAME="ResortsLite"
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

echo "=== Build and push ${IMAGE_NAME} ==="

if ! command -v docker >/dev/null 2>&1; then
  echo "ERROR: docker is not installed or not in PATH"
  exit 1
fi

echo "Select the registry:"
echo "  1. AWS ECR (Amazon Elastic Container Registry)"
echo "  2. Docker Hub"
read -r -p "Enter 1 or 2: " REGISTRY_CHOICE

if [ "$REGISTRY_CHOICE" = "1" ]; then
  if ! command -v aws >/dev/null 2>&1; then
    echo "ERROR: the AWS CLI is not installed or not in PATH"
    exit 1
  fi
  read -r -p "Enter the AWS account ID: " AWS_ACCOUNT_ID
  read -r -p "Enter the AWS region (for example us-east-1): " AWS_REGION
  if [ -z "$AWS_ACCOUNT_ID" ] || [ -z "$AWS_REGION" ]; then
    echo "ERROR: the AWS account ID and the region are required"
    exit 1
  fi
  REGISTRY_URL="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
  ECR_REPO="$IMAGE_NAME"
  REPO_PATH="${REGISTRY_URL}/${ECR_REPO}"
elif [ "$REGISTRY_CHOICE" = "2" ]; then
  read -r -p "Enter the Docker Hub username: " DOCKER_USERNAME
  read -r -s -p "Enter the Docker Hub password or access token: " DOCKER_PASSWORD
  echo ""
  if [ -z "$DOCKER_USERNAME" ] || [ -z "$DOCKER_PASSWORD" ]; then
    echo "ERROR: the Docker Hub username and password are required"
    exit 1
  fi
  DOCKER_USERNAME=$(echo "$DOCKER_USERNAME" | tr '[:upper:]' '[:lower:]')
  REPO_PATH="${DOCKER_USERNAME}/${IMAGE_NAME}"
else
  echo "ERROR: invalid choice, enter 1 or 2"
  exit 1
fi

read -r -p "Enter IMAGE_TAG (press Enter for latest): " IMAGE_TAG
IMAGE_TAG=$(echo "$IMAGE_TAG" | tr -cs 'A-Za-z0-9_.-' '-' | sed 's/^-*//;s/-*$//')
if [ -z "$IMAGE_TAG" ]; then
  IMAGE_TAG="latest"
fi

FULL_IMAGE_NAME="${REPO_PATH}:${IMAGE_TAG}"

echo "Building image ${FULL_IMAGE_NAME} ..."
docker build -f Dockerfile -t "$FULL_IMAGE_NAME" .
echo "Build finished."

if [ "$REGISTRY_CHOICE" = "1" ]; then
  echo "Logging in to AWS ECR ..."
  aws ecr get-login-password --region $AWS_REGION | docker login --username AWS --password-stdin $REGISTRY_URL
  echo "Checking the ECR repository ${ECR_REPO} ..."
  aws ecr describe-repositories --repository-names $ECR_REPO --region $AWS_REGION >/dev/null 2>&1 || aws ecr create-repository --repository-name $ECR_REPO --region $AWS_REGION
else
  echo "Logging in to Docker Hub ..."
  echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin
fi

echo "Pushing ${FULL_IMAGE_NAME} ..."
docker push "$FULL_IMAGE_NAME"

echo "Done. Image pushed: ${FULL_IMAGE_NAME}"
echo "Use this image URI with scripts/deploy-image.sh"

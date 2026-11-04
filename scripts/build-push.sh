#!/bin/bash
set -e

PROJECT_NAME="ResortsLite"
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

echo "=== Build and Push for $IMAGE_NAME ==="

# Ask for target platform
echo "Choose target platform:"
echo "1. Amazon ECR (AWS)"
echo "2. Docker Hub"
read -p "Enter choice (1 or 2): " PLATFORM_CHOICE

if [ "$PLATFORM_CHOICE" = "1" ]; then
    # Amazon ECR
    read -p "Enter AWS region: " AWS_REGION
    read -p "Enter AWS account ID: " AWS_ACCOUNT_ID
    read -p "Enter ECR repository name (or press Enter to use $IMAGE_NAME): " ECR_REPO
    ECR_REPO=${ECR_REPO:-$IMAGE_NAME}
    REGISTRY_URL="$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com"
    FULL_IMAGE_NAME="$REGISTRY_URL/$ECR_REPO"

    read -p "Enter image tag (or press Enter for latest): " IMAGE_TAG
    IMAGE_TAG=${IMAGE_TAG:-latest}

    # Login to ECR
    echo "Logging in to Amazon ECR..."
    aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$REGISTRY_URL"

    # Create repository if it doesn't exist
    aws ecr describe-repositories --repository-names "$ECR_REPO" --region "$AWS_REGION" >/dev/null 2>&1 || \
        aws ecr create-repository --repository-name "$ECR_REPO" --region "$AWS_REGION"

    # Build and push
    echo "Building Docker image..."
    docker build -f Dockerfile -t "$FULL_IMAGE_NAME:$IMAGE_TAG" .

    echo "Pushing Docker image..."
    docker push "$FULL_IMAGE_NAME:$IMAGE_TAG"

    echo "=== Success ==="
    echo "Image pushed to: $FULL_IMAGE_NAME:$IMAGE_TAG"

elif [ "$PLATFORM_CHOICE" = "2" ]; then
    # Docker Hub
    read -p "Enter Docker Hub username: " DOCKER_USERNAME
    read -s -p "Enter Docker Hub password: " DOCKER_PASSWORD
    echo ""
    read -p "Enter image tag (or press Enter for latest): " IMAGE_TAG
    IMAGE_TAG=${IMAGE_TAG:-latest}
    FULL_IMAGE_NAME="$DOCKER_USERNAME/$IMAGE_NAME"

    # Login to Docker Hub
    echo "Logging in to Docker Hub..."
    echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin

    # Build and push
    echo "Building Docker image..."
    docker build -f Dockerfile -t "$FULL_IMAGE_NAME:$IMAGE_TAG" .

    echo "Pushing Docker image..."
    docker push "$FULL_IMAGE_NAME:$IMAGE_TAG"

    echo "=== Success ==="
    echo "Image pushed to: $FULL_IMAGE_NAME:$IMAGE_TAG"

else
    echo "Invalid choice. Exiting."
    exit 1
fi

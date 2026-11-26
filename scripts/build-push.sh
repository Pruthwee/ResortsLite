#!/bin/bash
set -e

# Build and push script for Resortslite_Mono on AWS ECR

echo "=== Resortslite Build and Push ==="

# Project name
PROJECT_NAME="ResortsLite"
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

# Ask for target platform
echo ""
echo "Choose target platform:"
echo "1. AWS ECR"
echo "2. Docker Hub"
read -p "Enter choice (1 or 2): " PLATFORM_CHOICE

if [ "$PLATFORM_CHOICE" = "1" ]; then
    # AWS ECR
    read -p "Enter AWS region (e.g., us-east-1): " AWS_REGION
    read -p "Enter AWS account ID: " AWS_ACCOUNT_ID
    read -p "Enter ECR repository name: " ECR_REPO
    read -p "Enter image tag (press Enter for 'latest'): " IMAGE_TAG

    if [ -z "$IMAGE_TAG" ]; then
        IMAGE_TAG="latest"
    fi

    REGISTRY_URL="${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
    FULL_IMAGE_NAME="${REGISTRY_URL}/${ECR_REPO}:${IMAGE_TAG}"

    echo ""
    echo "=== Logging in to Amazon ECR ==="
    aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$REGISTRY_URL"

    echo ""
    echo "=== Creating ECR repository if it doesn't exist ==="
    aws ecr describe-repositories --repository-names "$ECR_REPO" --region "$AWS_REGION" >/dev/null 2>&1 || \
        aws ecr create-repository --repository-name "$ECR_REPO" --region "$AWS_REGION"

    echo ""
    echo "=== Building Docker image ==="
    docker build -f Dockerfile -t "$FULL_IMAGE_NAME" .

    echo ""
    echo "=== Pushing Docker image ==="
    docker push "$FULL_IMAGE_NAME"

    echo ""
    echo "=== Build and push complete ==="
    echo "Image: $FULL_IMAGE_NAME"

elif [ "$PLATFORM_CHOICE" = "2" ]; then
    # Docker Hub
    read -p "Enter Docker Hub username: " DOCKER_USERNAME
    read -s -p "Enter Docker Hub password: " DOCKER_PASSWORD
    echo ""
    read -p "Enter image tag (press Enter for 'latest'): " IMAGE_TAG

    if [ -z "$IMAGE_TAG" ]; then
        IMAGE_TAG="latest"
    fi

    FULL_IMAGE_NAME="${DOCKER_USERNAME}/${IMAGE_NAME}:${IMAGE_TAG}"

    echo ""
    echo "=== Logging in to Docker Hub ==="
    echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin

    echo ""
    echo "=== Building Docker image ==="
    docker build -f Dockerfile -t "$FULL_IMAGE_NAME" .

    echo ""
    echo "=== Pushing Docker image ==="
    docker push "$FULL_IMAGE_NAME"

    echo ""
    echo "=== Build and push complete ==="
    echo "Image: $FULL_IMAGE_NAME"

else
    echo "Invalid choice. Exiting."
    exit 1
fi

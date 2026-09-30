# AWS Secrets Manager Configuration Guide

## Overview
This application has been migrated from hard-coded database credentials to AWS Secrets Manager for secure credential management with automatic rotation support.

## AWS Secrets Manager Setup

### 1. Create Secret in AWS Secrets Manager

Using AWS CLI:
```bash
aws secretsmanager create-secret \
    --name resorts-db-credentials \
    --description "Database credentials for ResortsLite application" \
    --secret-string '{
        "host": "db-prod.resorts-internal.com",
        "username": "admin",
        "password": "YourSecurePassword"
    }' \
    --region us-east-1
```

Using AWS Console:
1. Navigate to AWS Secrets Manager in the AWS Console
2. Click "Store a new secret"
3. Select "Other type of secret"
4. Add the following key-value pairs:
   - `host`: Your database host (e.g., `db-prod.resorts-internal.com`)
   - `username`: Your database username (e.g., `admin`)
   - `password`: Your database password
5. Name the secret: `resorts-db-credentials`
6. Complete the wizard with default settings

### 2. IAM Permissions

Ensure your application's IAM role has the following permissions:

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "secretsmanager:GetSecretValue",
                "secretsmanager:DescribeSecret"
            ],
            "Resource": "arn:aws:secretsmanager:us-east-1:ACCOUNT_ID:secret:resorts-db-credentials-*"
        }
    ]
}
```

### 3. Environment Variables

Set the following environment variables for your application:

```bash
# Required
AWS_SECRET_NAME=resorts-db-credentials
AWS_REGION=us-east-1

# Optional - for local development fallback
DB_HOST=localhost
DB_USER=sa
DB_PASSWORD=
```

### 4. Application Configuration

The application is configured via `application.properties`:

```properties
# AWS Secrets Manager Configuration
aws.secretsmanager.secret.name=${AWS_SECRET_NAME:resorts-db-credentials}
aws.region=${AWS_REGION:us-east-1}
```

## Local Development

For local development without AWS Secrets Manager access, the application will fall back to environment variables:

```bash
export DB_HOST=localhost
export DB_USER=sa
export DB_PASSWORD=
```

## Secret Rotation

To enable automatic secret rotation:

1. In AWS Secrets Manager console, select your secret
2. Click "Edit rotation"
3. Enable automatic rotation
4. Choose rotation interval (e.g., 30 days)
5. Select or create a Lambda function for rotation

The application will automatically retrieve the updated credentials on the next secret fetch.

## Monitoring

Monitor secret access in CloudTrail:
- Event name: `GetSecretValue`
- Resource: `resorts-db-credentials`

## Troubleshooting

### Application fails to start
- Verify IAM role has `secretsmanager:GetSecretValue` permission
- Check secret name matches configuration
- Verify AWS region is correct
- Check CloudWatch logs for detailed error messages

### Credentials not updating after rotation
- Restart the application to fetch new credentials
- Consider implementing a credential refresh mechanism for zero-downtime rotation

## Security Best Practices

1. **Never commit credentials to source control**
2. **Use IAM roles** instead of access keys when running in AWS
3. **Enable CloudTrail logging** for secret access auditing
4. **Implement secret rotation** for production environments
5. **Use separate secrets** for different environments (dev, staging, prod)
6. **Restrict IAM permissions** to only the secrets needed by each application

## Migration Notes

### What Changed
- Removed hard-coded credentials from `BookingService.java` (lines 22-23)
- Created `DatabaseCredentialsConfig.java` to retrieve credentials from AWS Secrets Manager
- Added AWS Secrets Manager SDK dependency to `pom.xml`
- Updated `application.properties` with Secrets Manager configuration

### Backward Compatibility
The application maintains backward compatibility through environment variable fallback for local development.

## References
- [AWS Secrets Manager Documentation](https://docs.aws.amazon.com/secretsmanager/)
- [AWS SDK for Java v2 - Secrets Manager](https://sdk.amazonaws.com/java/api/latest/software/amazon/awssdk/services/secretsmanager/package-summary.html)
- [Spring Boot Externalized Configuration](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.external-config)

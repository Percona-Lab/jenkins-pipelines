# Justfile for jenkins-pipelines infrastructure management

# Default region and AWS profile
aws_region := "us-east-2"
aws_profile := "percona-dev-admin"
# removeUntaggedEc2 Lambda is deployed in eu-west-1 (but scans all regions)
cleanup_lambda_region := "eu-west-1"
# Dry-run mode for removeUntaggedEc2 (true = no deletions, false = perform deletions)
dry_run := env_var_or_default("DRY_RUN", "true")

# List all available recipes
default:
    @just --list

# ============================================================================
# AWS Lambda Functions (cloud/aws-functions/)
# ============================================================================

# Deploy email_running_instances Lambda function
deploy-lambda-email-running-instances:
    #!/usr/bin/env bash
    set -euo pipefail
    echo "Creating deployment package..."
    cd cloud/aws-functions
    rm -f email_running_instances.zip
    zip -q email_running_instances.zip email_running_instances.py

    echo "Deploying to AWS Lambda..."
    aws lambda update-function-code \
        --function-name email_running_instances \
        --zip-file fileb://email_running_instances.zip \
        --region {{aws_region}} \
        --profile {{aws_profile}}

    echo "Lambda function deployed successfully"
    rm email_running_instances.zip

# Lint all Lambda Python code
lint-lambdas:
    #!/usr/bin/env bash
    set -euo pipefail
    cd cloud/aws-functions

    echo "Running ruff linter on all Python files..."
    uv run --with ruff ruff check *.py

    echo "Running ruff formatter..."
    uv run --with ruff ruff format *.py

    echo "Linting complete"

# Show all Lambda functions info
info-lambdas:
    #!/usr/bin/env bash
    echo "Lambda Functions:"
    aws lambda list-functions \
        --region {{aws_region}} \
        --profile {{aws_profile}} \
        --query 'Functions[?starts_with(FunctionName, `email`) || starts_with(FunctionName, `orphaned`) || starts_with(FunctionName, `remove`)].{Name:FunctionName,Runtime:Runtime,Updated:LastModified}' \
        --output table

# ============================================================================
# CloudFormation Stacks (IaC/)
# ============================================================================

# Deploy StagingStack (PMM staging environment)
deploy-stack-staging:
    #!/usr/bin/env bash
    set -euo pipefail
    echo "Deploying StagingStack..."

    aws cloudformation update-stack \
        --stack-name pmm-staging \
        --template-body file://IaC/StagingStack.yml \
        --capabilities CAPABILITY_NAMED_IAM \
        --region {{aws_region}} \
        --profile {{aws_profile}}

    echo "Waiting for stack update to complete..."
    aws cloudformation wait stack-update-complete \
        --stack-name pmm-staging \
        --region {{aws_region}} \
        --profile {{aws_profile}}

    echo "StagingStack deployed successfully"

# Deploy LambdaVolumeCleanup stack
deploy-stack-volume-cleanup:
    #!/usr/bin/env bash
    set -euo pipefail
    echo "Deploying LambdaVolumeCleanup..."

    aws cloudformation update-stack \
        --stack-name lambda-volume-cleanup \
        --template-body file://IaC/LambdaVolumeCleanup.yml \
        --capabilities CAPABILITY_NAMED_IAM \
        --region {{aws_region}} \
        --profile {{aws_profile}}

    echo "Waiting for stack update to complete..."
    aws cloudformation wait stack-update-complete \
        --stack-name lambda-volume-cleanup \
        --region {{aws_region}} \
        --profile {{aws_profile}}

    echo "LambdaVolumeCleanup deployed successfully"

# List all CloudFormation stacks
list-stacks:
    #!/usr/bin/env bash
    aws cloudformation list-stacks \
        --region {{aws_region}} \
        --profile {{aws_profile}} \
        --stack-status-filter CREATE_COMPLETE UPDATE_COMPLETE \
        --query 'StackSummaries[].{Name:StackName,Status:StackStatus,Updated:LastUpdatedTime}' \
        --output table

# Describe a specific stack
describe-stack stack_name:
    #!/usr/bin/env bash
    aws cloudformation describe-stacks \
        --stack-name {{stack_name}} \
        --region {{aws_region}} \
        --profile {{aws_profile}} \
        --query 'Stacks[0].{Name:StackName,Status:StackStatus,Created:CreationTime,Updated:LastUpdatedTime}' \
        --output table

# ============================================================================
# Development & Testing
# ============================================================================

# Run all linters
lint: lint-lambdas

# Full deployment workflow for email Lambda (lint, deploy)
deploy-email-running-instances-full: lint-lambdas deploy-lambda-email-running-instances

# Check for trailing whitespaces in Python files
check-whitespace:
    #!/usr/bin/env bash
    set -euo pipefail
    echo "Checking for trailing whitespaces..."
    cd cloud/aws-functions
    if rg '\s+$' *.py; then
        echo "Found trailing whitespaces"
        exit 1
    else
        echo "No trailing whitespaces found"
    fi

# ============================================================================
# Infrastructure Health Checks
# ============================================================================

# Check running EC2 instances in staging
check-staging-instances:
    #!/usr/bin/env bash
    echo "Running PMM Staging Instances:"
    aws ec2 describe-instances \
        --region {{aws_region}} \
        --profile {{aws_profile}} \
        --filters "Name=tag:iit-billing-tag,Values=pmm-staging" "Name=instance-state-name,Values=running" \
        --query 'Reservations[].Instances[].{Name:Tags[?Key==`Name`]|[0].Value,Type:InstanceType,State:State.Name,LaunchTime:LaunchTime}' \
        --output table

# Check CloudFormation stacks in DELETE_FAILED state
check-failed-stacks:
    #!/usr/bin/env bash
    echo "Failed CloudFormation Stacks:"
    aws cloudformation list-stacks \
        --region {{aws_region}} \
        --profile {{aws_profile}} \
        --stack-status-filter DELETE_FAILED \
        --query 'StackSummaries[].{Name:StackName,Status:StackStatus,Reason:StackStatusReason}' \
        --output table

# ============================================================================
# removeUntaggedEc2 Lambda (IaC/RemoveUntaggedEc2Stack.yml)
# ============================================================================

# Deploy RemoveUntaggedEc2 CloudFormation stack
deploy-stack-remove-untagged-ec2:
    #!/usr/bin/env bash
    set -euo pipefail
    echo "Deploying RemoveUntaggedEc2Stack..."

    aws cloudformation deploy \
        --stack-name remove-untagged-ec2 \
        --template-file IaC/RemoveUntaggedEc2Stack.yml \
        --capabilities CAPABILITY_NAMED_IAM \
        --parameter-overrides EksSkipPattern="pe-.*" DryRun="{{dry_run}}" \
        --region {{cleanup_lambda_region}} \
        --profile {{aws_profile}}

    echo "RemoveUntaggedEc2Stack deployed successfully"

# Update RemoveUntaggedEc2 stack (sync from .py file)
update-stack-remove-untagged-ec2:
    #!/usr/bin/env bash
    set -euo pipefail
    echo "Syncing cloud/aws-functions/removeUntaggedEc2.py to CloudFormation..."
    echo "Manual sync required:"
    echo "   1. Copy code from cloud/aws-functions/removeUntaggedEc2.py"
    echo "   2. Paste into IaC/RemoveUntaggedEc2Stack.yml under Code.ZipFile"
    echo "   3. Run: just deploy-stack-remove-untagged-ec2"
    echo ""
    echo "Or deploy directly to Lambda: just deploy-lambda-remove-untagged-ec2"

# Deploy removeUntaggedEc2 directly to Lambda (bypass CloudFormation)
deploy-lambda-remove-untagged-ec2:
    #!/usr/bin/env bash
    set -euo pipefail
    echo "Creating deployment package..."
    cd cloud/aws-functions
    rm -f removeUntaggedEc2.zip
    zip -q removeUntaggedEc2.zip removeUntaggedEc2.py

    echo "Deploying to AWS Lambda..."
    aws lambda update-function-code \
        --function-name removeUntaggedEc2 \
        --zip-file fileb://removeUntaggedEc2.zip \
        --region {{cleanup_lambda_region}} \
        --profile {{aws_profile}}

    echo "Lambda function deployed successfully"
    rm removeUntaggedEc2.zip

# Delete RemoveUntaggedEc2 CloudFormation stack
delete-stack-remove-untagged-ec2:
    #!/usr/bin/env bash
    set -euo pipefail
    echo "This will delete the removeUntaggedEc2 Lambda and EventBridge rule"
    read -p "Are you sure? (yes/no): " confirm
    if [ "$confirm" != "yes" ]; then
        echo "Aborted"
        exit 1
    fi

    aws cloudformation delete-stack \
        --stack-name remove-untagged-ec2 \
        --region {{cleanup_lambda_region}} \
        --profile {{aws_profile}}

    echo "Waiting for stack deletion..."
    aws cloudformation wait stack-delete-complete \
        --stack-name remove-untagged-ec2 \
        --region {{cleanup_lambda_region}} \
        --profile {{aws_profile}}

    echo "Stack deleted"

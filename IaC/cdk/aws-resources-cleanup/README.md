# AWS Resources Cleanup

Automated cleanup Lambda for EC2 instances, EKS clusters, and OpenShift infrastructure.

## Overview

Comprehensive resource cleanup Lambda that runs hourly to enforce TTL policies, billing tag compliance, and infrastructure lifecycle management across all AWS regions.

**Default Mode**: DRY_RUN (logs actions without execution)

## Features

### EC2 Cleanup
- **TTL Expiration**: Enforces `creation-time` + `delete-cluster-after-hours` tags
- **Stop Policy**: Honors `stop-after-days` for staging instances
- **Long Stopped**: Terminates instances stopped >30 days
- **Untagged Instances**: Removes instances without valid billing tags after grace period
- **Billing Tag Validation**: Validates category strings or Unix timestamps

**Protected Instances**:
- Persistent billing tags: `jenkins-*`, `pmm-dev`
- Valid `iit-billing-tag` timestamps
- Instances with `Name` tag matching protected patterns

### EKS Cleanup
- CloudFormation stack deletion with configurable skip patterns
- Default protection: `pe-.*` (platform engineering clusters)

### OpenShift Cleanup
Full cluster destruction including:
- Compute: EC2 instances
- Network: VPC, subnets, route tables, internet gateways, NAT gateways, ENIs, Elastic IPs
- Load Balancers: Classic ELB, ALB, NLB
- Security: Security groups, VPC endpoints
- DNS: Route53 hosted zones and records
- Storage: S3 buckets with `kubernetes.io/cluster/<cluster-name>` tags

**Reconciliation Loop**: Retries resource deletion to handle dependency ordering

## Environment Variables

| Variable | Type | Default | Description |
|----------|------|---------|-------------|
| `DRY_RUN` | bool | `true` | Preview mode - logs actions without execution |
| `SNS_TOPIC_ARN` | string | `""` | SNS topic for cleanup notifications |
| `UNTAGGED_THRESHOLD_MINUTES` | int | `30` | Grace period for untagged instances before deletion |
| `EKS_SKIP_PATTERN` | string | `pe-.*` | Regex pattern for protected EKS cluster names |
| `OPENSHIFT_CLEANUP_ENABLED` | bool | `true` | Enable OpenShift cluster cleanup |
| `OPENSHIFT_BASE_DOMAIN` | string | `cd.percona.com` | Route53 base domain for OpenShift clusters |
| `OPENSHIFT_MAX_RETRIES` | int | `3` | Max reconciliation attempts for resource deletion |

**Runtime**: Python 3.12, 1024 MB memory, 10 minute timeout, hourly schedule

## Quick Start

### Prerequisites
```bash
brew install uv       # Python package manager
brew install just     # Task runner
```

### Deploy
```bash
cd IaC/cdk/aws-resources-cleanup

# Install dependencies and bootstrap CDK (first time)
just install
just bootstrap

# Deploy in DRY_RUN mode (safe)
just deploy
```

### Monitor
```bash
just logs           # Tail CloudWatch logs
just invoke-aws     # Manual invocation
just info           # Lambda configuration
```

## Common Commands

| Command | Description |
|---------|-------------|
| `just deploy` | Deploy in DRY_RUN mode |
| `just deploy-live` | Deploy in LIVE mode (⚠️ destructive!) |
| `just logs` | Tail CloudWatch logs (follow) |
| `just logs-recent` | Show logs from last hour |
| `just update-code` | Fast Lambda code update (no CDK) |
| `just update-env DRY_RUN=false` | Switch to LIVE mode |
| `just diff` | Preview infrastructure changes |
| `just destroy` | Remove entire stack |

Run `just` to see all commands.

## Cleanup Policies

Policies are evaluated in priority order:

1. **TTL Expiration**
   - Tags: `creation-time` (Unix timestamp) + `delete-cluster-after-hours` (integer)
   - Action: Terminate when TTL expires

2. **Stop Policy**
   - Tag: `stop-after-days` (integer)
   - Action: Stop instance after specified days

3. **Long Stopped**
   - Criteria: Instance in stopped state >30 days
   - Action: Terminate

4. **Untagged**
   - Criteria: Missing or invalid `iit-billing-tag`
   - Action: Terminate after grace period (default: 30 minutes)

**Protection**: Instances with persistent billing tags or valid timestamps are never auto-deleted.

# AWS Resources Cleanup

Automated cleanup Lambda for EC2 instances, EBS volumes, EKS clusters, and OpenShift infrastructure.

## Overview

Comprehensive resource cleanup Lambda that enforces TTL policies, billing tag compliance, and infrastructure lifecycle management across AWS regions.

**Default Mode**: DRY_RUN (logs actions without execution)
**Architecture**: ARM64 (Graviton2) for 20% cost savings
**Concurrency**: Reserved execution limit of 1 to prevent race conditions

## Features

### EC2 Instance Cleanup
- **TTL Expiration**: Enforces `creation-time` + `delete-cluster-after-hours` tags
- **Stop Policy**: Honors `stop-after-days` for staging instances
- **Long Stopped**: Terminates instances stopped >30 days
- **Untagged Instances**: Removes instances without valid billing tags after grace period
- **Billing Tag Validation**: Validates category strings or Unix timestamps

**Protected Instances**:
- Persistent billing tags: `jenkins-*`, `pmm-dev`
- Valid `iit-billing-tag` timestamps (category or future expiration)
- TTL tags take precedence over billing tag protection

### EBS Volume Cleanup
- **Unattached Volumes**: Deletes available (unattached) volumes without protection
- **Age Tracking**: Reports volume ages and statistics in cleanup summaries
- **Protection Mechanisms**:
  - `PerconaKeep` tag
  - Name contains "do not remove"
  - Valid billing tags (persistent or category)

### EKS Cleanup
- CloudFormation stack deletion (`eksctl-*` stacks)
- Configurable skip patterns (default: `pe-.*` for production clusters)
- Optional: Instance-only termination when EKS cleanup disabled

### OpenShift Cleanup
Full cluster destruction including:
- **Compute**: EC2 instances
- **Network**: VPC, subnets, route tables, internet gateways, NAT gateways, ENIs, Elastic IPs
- **Load Balancers**: Classic ELB, ALB, NLB, target groups
- **Security**: Security groups, VPC endpoints
- **DNS**: Route53 hosted zones and records
- **Storage**: S3 buckets with cluster tags

**Reconciliation Loop**: Retries resource deletion to handle dependency ordering (max 3 retries)

## Configuration Parameters

Stack parameters can be set during deployment or updated via CloudFormation console:

### Core Settings
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `DryRunMode` | String | `true` | [SAFETY] Safe mode - logs actions without executing. Always test with 'true' first |
| `ScheduleRateMinutes` | Number | `15` | [SCHEDULING] Execution frequency. Recommended: 15 normal, 5 aggressive, 60 light |
| `NotificationEmail` | String | `""` | [NOTIFICATIONS] Email for cleanup reports (requires manual SNS subscription) |

### Region & Logging
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `TargetRegions` | String | `all` | [REGION FILTER] Regions to scan. Use 'all' or 'us-east-1,us-west-2' |
| `LogLevel` | String | `INFO` | [LOGGING] DEBUG=detailed, INFO=standard, WARNING=issues, ERROR=failures |
| `LogRetentionDays` | Number | `30` | [LOGGING] CloudWatch retention. Options: 1,3,7,14,30,60,90,120,180 |

### Cleanup Policies
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `UntaggedThresholdMinutes` | Number | `30` | [POLICY] Grace period for untagged instances (10-1440 minutes) |
| `StoppedThresholdDays` | Number | `30` | [POLICY] Days before terminating stopped instances (7-180 days) |

### Resource-Specific Settings
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `VolumeCleanupEnabled` | String | `true` | [VOLUMES] Enable unattached volume cleanup |
| `EKSCleanupEnabled` | String | `true` | [EKS] Enable full cluster deletion via CloudFormation |
| `EKSSkipPattern` | String | `pe-.*` | [EKS] Regex for protected cluster names |
| `OpenShiftCleanupEnabled` | String | `true` | [OPENSHIFT] Enable comprehensive cluster cleanup |
| `OpenShiftBaseDomain` | String | `cd.percona.com` | [OPENSHIFT] Route53 base domain for DNS cleanup |
| `OpenShiftMaxRetries` | Number | `3` | [OPENSHIFT] Max reconciliation attempts (1-5) |

**Runtime**: Python 3.12 on ARM64, 1024 MB memory, 600s timeout

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

# Deploy in LIVE mode (destructive!)
just deploy-live
```

### Monitor
```bash
just logs           # Tail CloudWatch logs (follow)
just logs-recent    # Show logs from last hour
just invoke-aws     # Manual invocation
just info           # Lambda configuration
just params         # Show all stack parameters
just outputs        # Show stack outputs
```

## Common Commands

| Command | Description |
|---------|-------------|
| `just deploy` | Deploy in DRY_RUN mode (safe) |
| `just deploy-live` | Deploy in LIVE mode (⚠️ destructive!) |
| `just diff` | Preview infrastructure changes |
| `just logs` | Tail CloudWatch logs (follow) |
| `just logs-recent` | Show logs from last hour |
| `just invoke-aws` | Manually invoke Lambda |
| `just info` | Show Lambda configuration |
| `just params` | Show all stack parameters |
| `just outputs` | Show stack outputs |
| `just update-code` | Fast Lambda code update (no CDK) |
| `just test` | Run unit tests |
| `just test-coverage` | Run tests with detailed coverage |
| `just lint` | Run linters (ruff, black, mypy) |
| `just format` | Format code |
| `just ci` | Full CI pipeline (lint + test + synth) |
| `just destroy` | Remove entire stack |

Run `just` to see all commands.

## Enhanced Logging

The Lambda provides detailed, consistent logging across all resource types:

### Protection Logging
```
Instance i-0d09... protected: Valid billing tag 'ps-package-testing' (rhel8-arm-ps_80)
Volume vol-01... protected: Name contains 'do not remove' (jenkins-ps80 DATA)
```

### Action Logging
```
[DRY-RUN] Would TERMINATE instance i-085e... in us-east-2: Missing billing tag. Running 718 minutes
[DRY-RUN] Would DELETE volume vol-070... in us-west-2: Unattached volume (100GB gp3, 273.2 days old)
[DRY-RUN] Would destroy OpenShift cluster: helm-test-87-j4mfk (TTL expired 8 days ago)
```

### Region Summaries
```
Instance scan for us-west-2: 11 scanned, 1 actions, 10 protected
  - Valid billing tag 'pxb-package-testing': 3
  - Persistent billing tag 'jenkins-pxb': 1

Volume scan for us-west-2: 5 scanned, 1 actions, 4 protected
  - Name contains 'do not remove': 2
  - Valid billing tag 'dev': 2

Completed us-west-2 in 0.8s: 11 instances (1 actions), 5 volumes (1 actions)
```

### Final Summary
```
Cleanup complete: 31 actions across 17 regions (15.4s total)
  TERMINATE: 8
  DELETE_VOLUME: 17
  TERMINATE_OPENSHIFT_CLUSTER: 6
  Volume ages: 80.5-278.5 days (avg: 166.4 days)
```

## Cleanup Policies

Policies are evaluated in priority order:

1. **TTL Expiration**
   - Tags: `creation-time` (Unix timestamp) + `delete-cluster-after-hours` (integer)
   - Action: TERMINATE instance or TERMINATE_CLUSTER
   - Example: Created at 1000000, TTL 8h → Expires at 1000000 + 28800

2. **Stop Policy**
   - Tag: `stop-after-days` (integer)
   - Action: STOP running instance after specified days
   - Only applies to running instances

3. **Long Stopped**
   - Criteria: Instance in stopped state >30 days (configurable via `StoppedThresholdDays`)
   - Action: TERMINATE
   - Helps reduce costs from forgotten stopped instances

4. **Untagged**
   - Criteria: Missing or invalid `iit-billing-tag`
   - Grace Period: 30 minutes (configurable via `UntaggedThresholdMinutes`)
   - Action: TERMINATE

**Protection**: Instances with persistent billing tags or valid category/timestamp tags are protected unless they have TTL tags (TTL takes precedence).

## Volume Cleanup

Unattached (available) EBS volumes are cleaned up based on:

- **State**: Must be `available` (not attached)
- **Protection**: Volumes with `PerconaKeep` tag, "do not remove" in Name, or valid billing tags are protected
- **Age Reporting**: Cleanup summary includes volume age statistics
- **Toggle**: Can be disabled via `VolumeCleanupEnabled` parameter

## Architecture

```
EventBridge (Schedule)
    ↓
Lambda (ARM64, Python 3.12)
    ├─ EC2 Instance Cleanup
    │   ├─ TTL Enforcement
    │   ├─ Stop Policy
    │   ├─ Long Stopped Detection
    │   └─ Untagged Cleanup
    ├─ EBS Volume Cleanup
    │   └─ Unattached Volume Detection
    ├─ EKS Cluster Cleanup
    │   └─ CloudFormation Stack Deletion
    └─ OpenShift Cluster Cleanup
        ├─ VPC & Network
        ├─ Load Balancers
        ├─ Route53 DNS
        └─ S3 Buckets
    ↓
SNS Notifications (optional)
```

## Dynamic Configuration

The justfile automatically retrieves the Lambda function name from CDK stack outputs, ensuring alignment between infrastructure definition and operational commands. If you change the function name in the CDK stack, all justfile commands will automatically use the new name after deployment.

## Development

### Testing
```bash
just test              # Run unit tests
just test-coverage     # Run tests with detailed coverage report
```

**Coverage**: 87% (176 tests passing)

### Code Quality
```bash
just lint              # Run ruff, black, mypy
just format            # Auto-format code with black and ruff
```

### Local Development
```bash
just update-code       # Fast code-only update (skips CDK)
just validate          # Validate CloudFormation template
```

## Safety Features

1. **DRY_RUN Default**: Always deploys in safe mode first
2. **Reserved Concurrency**: Limit of 1 prevents concurrent executions
3. **Protection Checks**: Multiple layers (tags, names, billing validation)
4. **Region Filtering**: Target specific regions to limit scope
5. **Configurable Thresholds**: Adjust grace periods and policies
6. **Detailed Logging**: Track all protection decisions and actions

## Troubleshooting

### Check Logs
```bash
just logs-recent       # Last hour of logs
just logs              # Follow logs in real-time
```

### Verify Configuration
```bash
just params            # Show all stack parameters
just info              # Show Lambda configuration
```

### Test Manually
```bash
just invoke-aws        # Trigger Lambda execution
```

### Common Issues

**OpenShift cleanup fails with dependency errors**
- Solution: The reconciliation loop automatically retries (default: 3 attempts)
- Resources are deleted in dependency order with delays between critical operations

**Volume cleanup not working**
- Check: `VolumeCleanupEnabled` parameter is set to `true`
- Check: Volumes are in `available` state (not attached)
- Check: Volumes don't have protection tags

**No actions taken**
- Verify: `DryRunMode` parameter (set to `false` for LIVE mode)
- Check: Resources match cleanup policies and aren't protected

## Contributing

### Running Tests
```bash
just test              # Unit tests
just test-coverage     # Coverage report
just lint              # Code quality checks
```

### Adding New Policies
1. Add policy function in `lambda/aws_resource_cleanup/ec2/policies.py`
2. Update policy priority order in `handler.py`
3. Add unit tests in `tests/unit/policies/`
4. Update this README

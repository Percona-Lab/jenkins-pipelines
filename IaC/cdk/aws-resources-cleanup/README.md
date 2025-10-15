# AWS Resources Cleanup

Automated Lambda for EC2, EBS, EKS, and OpenShift cleanup across AWS regions.

**Runtime**: Python 3.12 ARM64, 1024MB, 600s timeout
**Default**: DRY_RUN mode (logs only)
**Concurrency**: 1 (prevents race conditions)

## Features

- **EC2**: TTL expiration, stop policy, long-stopped instances, untagged cleanup
- **EBS**: Unattached volume deletion
- **EKS**: CloudFormation stack deletion (skip pattern: `pe-.*`)
- **OpenShift**: Full cluster cleanup (VPC, ELB, Route53, S3)

**Protection**: Persistent tags (`jenkins-*`, `pmm-dev`), valid billing tags, `PerconaKeep`, "do not remove" in names

## Quick Start

```bash
brew install uv just
cd IaC/cdk/aws-resources-cleanup
just install
just bootstrap      # First time only
just deploy         # DRY_RUN mode
just deploy-live    # LIVE mode (destructive!)
```

## Commands

```bash
just deploy         # Deploy (DRY_RUN)
just logs           # Tail logs
just invoke-aws     # Manual trigger
just params         # Show config
just test           # Run tests (176, 87% coverage)
```

Run `just` for all commands.

## Configuration

Key parameters (CloudFormation):

| Parameter | Default | Description |
|-----------|---------|-------------|
| `DryRunMode` | `true` | Safe mode |
| `ScheduleRateMinutes` | `15` | Run frequency |
| `TargetRegions` | `all` | Regions to scan |
| `LogLevel` | `INFO` | Log verbosity |
| `UntaggedThresholdMinutes` | `30` | Grace period |
| `VolumeCleanupEnabled` | `true` | Enable volume cleanup |
| `EKSCleanupEnabled` | `true` | Enable EKS cleanup |
| `OpenShiftCleanupEnabled` | `true` | Enable OpenShift cleanup |

View all: `just params`

## Cleanup Policies

Priority order:

1. **TTL** - `creation-time` + `delete-cluster-after-hours` → TERMINATE
2. **Stop** - `stop-after-days` → STOP
3. **Long Stopped** - >30 days → TERMINATE
4. **Untagged** - Missing `iit-billing-tag` → TERMINATE

## Logging

```
Instance i-0d09... protected: Valid billing tag 'ps-package-testing'
[DRY-RUN] Would TERMINATE instance i-085e... in us-east-2: Missing billing tag
Instance scan for us-west-2: 11 scanned, 1 actions, 10 protected
Cleanup complete: 31 actions across 17 regions (15.4s)
```

## Troubleshooting

```bash
just logs-recent    # Check logs
just params         # Verify config
just invoke-aws     # Test manually
```

**Issues:**
- No actions: Set `DryRunMode=false`
- Volume cleanup fails: Check `VolumeCleanupEnabled=true`, volumes `available`
- OpenShift errors: Auto-retries 3 times

## Architecture

```
EventBridge → Lambda → EC2/Volumes/EKS/OpenShift → SNS
```

Justfile retrieves function name from CDK outputs for alignment.

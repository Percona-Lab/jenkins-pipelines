# OpenShift Cluster Cleanup

Automated Lambda that cleans up expired OpenShift/ROSA clusters. Runs every 15 minutes, scans for expired clusters, deletes all resources (VPC, ELB, Route53, S3, EC2).

## Configuration

| Setting | Default | How to Change |
|---------|---------|---------------|
| **Lambda Location** | `us-east-2` | `AWS_REGION=us-west-1 just deploy` |
| **Scan Regions** (comma-separated) | `all` | `just deploy us-east-2` or `just deploy us-east-1,eu-west-1,ap-south-1` |
| **AWS Profile** | `default` | `AWS_PROFILE=myprofile just deploy` |
| **Mode** | `LIVE` | `just deploy-dry` |

## Quick Start

```bash
brew install uv just && aws configure
cd IaC/cdk/openshift-resources-cleanup
just install && just bootstrap
just deploy                         # Scans all regions (default)
just deploy us-east-2               # Scans us-east-2 only
AWS_REGION=us-west-1 just deploy   # Deploy Lambda to us-west-1
```

## Commands

```bash
just deploy [regions]    # Deploy LIVE (default: all regions)
just deploy-dry [regions]# Deploy DRY_RUN (toggles LIVE off)
just logs                # Tail CloudWatch logs
just params              # Show configuration
just test                # Run tests
```

Run `just` for all commands.

## How It Works

1. **Detect**: Scans EC2 for OpenShift/ROSA clusters (tags: `red-hat-clustertype: rosa` or name: `*-master-*`)
2. **Check TTL**: Reads `creation-time` + `delete-cluster-after-hours` tags, skips if not expired
3. **Delete**: Removes all resources in dependency order (instances → ELB → NAT → VPC → Route53 → S3)

## Logs & Troubleshooting

```bash
just logs           # View real-time logs
just params         # Check configuration
```

**Example output:**
```
Detected OpenShift ROSA cluster: jvp-rosa1-qmdkk
Cluster TTL not expired (5.45 hours remaining)
```

**Common issues:** Missing TTL tags (`creation-time`, `delete-cluster-after-hours`), `DryRunMode=true`, permissions

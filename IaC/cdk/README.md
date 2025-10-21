# CDK Projects

AWS CDK (Cloud Development Kit) infrastructure as code implementations.

## Projects

### aws-resources-cleanup
Comprehensive AWS resource cleanup Lambda with CDK deployment.

**Purpose**: Automated cleanup of EC2 instances, EKS clusters, and OpenShift infrastructure based on TTL policies and billing tags.

**Features**:
- TTL-based expiration (8h, 24h policies)
- Billing tag validation (category + Unix timestamps)
- EKS CloudFormation deletion
- OpenShift comprehensive cleanup (VPC, ELB, Route53, S3, NAT, security groups)
- DRY_RUN mode (default)
- SNS notifications
- Hourly EventBridge schedule

**Quick Start**:
```bash
cd aws-resources-cleanup
just install          # Install dependencies
just deploy           # Deploy in DRY_RUN mode
just logs             # Tail CloudWatch logs
```

📖 **Full documentation**: [aws-resources-cleanup/README.md](aws-resources-cleanup/README.md)

## Requirements

- AWS CLI configured with appropriate profile
- `uv` package manager: `brew install uv`
- `just` task runner: `brew install just`

## Common Commands

All projects use Justfile for consistent automation:

| Command | Description |
|---------|-------------|
| `just install` | Install all dependencies |
| `just synth` | Generate CloudFormation template |
| `just diff` | Preview infrastructure changes |
| `just deploy` | Deploy stack |
| `just destroy` | Remove stack |
| `just logs` | Tail CloudWatch logs (if applicable) |

## Adding New CDK Projects

When creating a new CDK project in this directory:

1. Create project directory: `mkdir project-name`
2. Initialize CDK: `cdk init app --language python`
3. Add Justfile for automation
4. Add project-specific README.md
5. Update this README with project description

## Resources

- [AWS CDK Documentation](https://docs.aws.amazon.com/cdk/)
- [CDK Python API Reference](https://docs.aws.amazon.com/cdk/api/v2/python/)
- [Justfile Documentation](https://github.com/casey/just)

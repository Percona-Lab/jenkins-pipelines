# CDK Projects

AWS CDK infrastructure as code for automated resource management.

## openshift-resources-cleanup

Automated Lambda that cleans up expired OpenShift/ROSA clusters. Scans all AWS regions every 15 minutes, validates TTL tags, and deletes cluster infrastructure (VPC, ELB, Route53, S3, EC2).

```bash
cd openshift-resources-cleanup
just install && just bootstrap
just deploy              # Deploy LIVE mode
just logs                # View logs
```

See [openshift-resources-cleanup/README.md](openshift-resources-cleanup/README.md) for full documentation.

## Requirements

- AWS CLI configured
- `brew install uv just`

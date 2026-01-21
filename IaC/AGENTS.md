# AGENTS.md - Infrastructure as Code

Extends: [../AGENTS.md](../AGENTS.md)

## TL;DR

**What**: Lambda cleanup (EC2/Volume/OpenShift), Jenkins CF templates, CDK stacks
**Files**: 3 Lambda YML + 1 CDK stack + 14 CF templates + 12 instance configs
**Protection**: `PerconaKeep` tag or "do not remove" in Name
**Schedules**: EC2 every 15min, Volume daily, CDK EventBridge

## Lambda Functions

| Function | File | Schedule | Purpose | Runtime |
|----------|------|----------|---------|---------|
| LambdaEC2Cleanup | LambdaEC2Cleanup.yml | Every 15 min | Terminate EC2 >10min without tags, auto-tag CirrusCI | Python 3.12 |
| LambdaVolumeCleanup | LambdaVolumeCleanup.yml | Daily | Delete orphaned EBS volumes | Python 3.8 |
| AWSResourcesCleanup | cdk/aws-resources-cleanup/ | EventBridge | EC2, EKS, OpenShift cleanup with DRY_RUN | Python Powertools |

**Protection tags**:
- `PerconaKeep` - Never delete
- `iit-billing-tag` - Persistent billing tag
- "do not remove" in Name - Manual protection

**CDK modules**: `handler.py`, `ec2/`, `eks/`, `openshift/`, `models/`, `utils/`

## CloudFormation Stacks

| Stack | Purpose | Key Resources |
|-------|---------|---------------|
| NewtJenkinsStack.yml | Base Jenkins master template | VPC, Subnets, IAM, EC2 |
| StagingStack.yml | PMM staging environment | VPC (10.178.0.0/22), Lambda email notify |
| PmmRdsStack.yml | PMM RDS testing | MySQL 5.6/5.7, Aurora, IAM |
| BackupBucket.yml | Encrypted S3 backup | KMS key, S3 with policy |
| DocsBucket.yml | Documentation storage | S3 bucket |
| JenkinsImagesECR.yml | ECR repos for build images | 4 public repos (ps-build, pxc-build, rpmbuild, pmm-server) |
| JenkinsArtifactoryIAM.yml | IAM for artifact access | IAM user with ECR perms |
| JenkinsArtifactoryAndSpawnIAM.yml | IAM artifacts + spawn | IAM extended perms |
| LambdaEC2Cleanup.yml | EC2 cleanup Lambda | Lambda, EventBridge, IAM |
| LambdaVolumeCleanup.yml | Volume cleanup Lambda | Lambda, EventBridge, IAM |
| spot-price-auto-updater.yml | Spot price updater job | Jenkins pipeline DSL |
| SpotTerminationReport.yml | Spot termination reporting | CloudFormation resources |
| disconnect-spot-instances.yml | Spot disconnect handling | CloudFormation resources |
| reconnect-workers.yml | Worker reconnection | CloudFormation resources |

## Jenkins Instances

| Directory | Product | Type | Size | Special |
|-----------|---------|------|------|---------|
| pmm.cd | PMM | CloudFormation | 34KB | - |
| ps80.cd | PS 8.0 | CloudFormation | 32KB | - |
| ps57.cd | PS 5.7 | CloudFormation | 32KB | Legacy |
| ps56.cd | PS 5.6 | CloudFormation | 31KB | Legacy |
| ps3.cd | PS 3 | CloudFormation | 32KB | Legacy |
| psmdb.cd | PSMDB/PBM | CloudFormation | 29KB | - |
| pxc.cd | PXC | CloudFormation | 32KB | - |
| pxb.cd | PXB | **Terraform** | - | Uses TF not CF |
| cloud.cd | Operators | CloudFormation | 33KB | - |
| pg.cd | PostgreSQL | CloudFormation | 34KB | - |
| fb.cd | Facebook/Meta | CloudFormation | 32KB | Legacy |
| rel.cd | Releases | CloudFormation | 32KB | - |

**Common pattern**: `JenkinsStack.yml` + `init.groovy.d/` (cloud.groovy, matrix.groovy)
**Exception**: `pxb.cd/` uses Terraform (8 files)

## CDK Deployment

```bash
# Deploy cleanup stack
cd IaC/cdk/aws-resources-cleanup
cdk deploy AWSResourcesCleanupStack

# With parameters
cdk deploy --parameters DryRun=true --parameters TargetRegions=us-east-1,us-east-2
```

## CloudFormation Deployment

```bash
# Create stack
aws cloudformation create-stack --stack-name LambdaEC2Cleanup \
  --template-body file://IaC/LambdaEC2Cleanup.yml \
  --capabilities CAPABILITY_IAM

# Update stack
aws cloudformation update-stack --stack-name LambdaEC2Cleanup \
  --template-body file://IaC/LambdaEC2Cleanup.yml \
  --capabilities CAPABILITY_IAM
```

## Shared Init Scripts

| File | Purpose |
|------|---------|
| init.groovy.d/ami-defs.properties | Central AMI definitions (8.6KB) |
| init.groovy.d/plugins.groovy | Plugin installation |
| scripts/cleanup_repo_script.sh | Repository cleanup (6KB) |
| find-ami.sh | AMI discovery |
| find-sg.sh | Security group discovery |

## Related

- [vars/AGENTS.md](../vars/AGENTS.md) - Shared helpers (openshiftCluster, runSpotInstance)
- [cloud/AGENTS.md](../cloud/AGENTS.md) - Operator cleanup patterns
- [pmm/AGENTS.md](../pmm/AGENTS.md) - PMM infrastructure (EKS HA, staging)
- [resources/AGENTS.md](../resources/AGENTS.md) - Python helper scripts

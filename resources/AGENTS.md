# AGENTS.md - Resources (Helper Scripts)

Extends: [../AGENTS.md](../AGENTS.md)

## TL;DR

**What**: Python helper scripts for DigitalOcean cleanup, AWS orphan resource cleanup
**Files**: 1 PMM script + 7 cloud cleanup scripts (all Lambda-ready)
**Pattern**: Tag-based TTL cleanup with `lambda_handler(event, context)`

## PMM Scripts

| Script | Purpose | Dependencies | Used By |
|--------|---------|--------------|---------|
| pmm/do_remove_droplets.py | DigitalOcean droplet cleanup | digitalocean, argparse | pmm/v3/pmm3-ovf-staging-stop.groovy |

**Pattern**: Removes droplets tagged `jenkins-pmm` older than 1 day
**Usage**: `python3 do_remove_droplets.py [-o NAME_OR_IP]`

## Cloud Cleanup Scripts

Location: `cloud/aws-functions/`

| Script | Purpose | Lines | Tags Required |
|--------|---------|-------|---------------|
| orphaned_eks_clusters.py | Delete expired EKS clusters + node groups | 161 | team=cloud, delete-cluster-after-hours |
| orphaned_openshift_instances.py | Terminate expired OpenShift EC2s | 68 | team=cloud, delete-cluster-after-hours |
| orphaned_cloudformation.py | Delete expired CF stacks | 147 | team=cloud, delete-cluster-after-hours |
| orphaned_vpcs.py | Cleanup VPCs (LBs, NAT, IGW, subnets, etc.) | 320 | team=cloud |
| orphaned_openshift_eip.py | Release orphaned Elastic IPs | 78 | OpenShift naming patterns |
| orphaned_openshift_users.py | Delete orphaned IAM users | 66 | openshift* username patterns |
| orphaned_oidc.py | Delete unused OIDC providers | 52 | eks.amazonaws.com OIDC |

**Shared**: `utils.py` - `get_regions_list()` (6 lines)

**Pattern**: All scripts have `lambda_handler(event, context)` for Lambda deployment
**Multi-region**: All scripts iterate AWS regions except `orphaned_openshift_users` (IAM is global)

## OpenShift Detection

| Script | Location | Purpose |
|--------|----------|---------|
| test_openshift_detection.py | Root | Test OpenShift cluster detection patterns |

**Detection patterns**:
- ROSA: `kubernetes.io/cluster/{name}` VPC tags
- Red Hat managed: VPC naming conventions
- Cluster API: `sigs.k8s.io/cluster-api-provider-aws/cluster/` tags

## Related

- [IaC/AGENTS.md](../IaC/AGENTS.md) - Lambda functions (deploy these scripts)
- [pmm/AGENTS.md](../pmm/AGENTS.md) - PMM infrastructure (uses do_remove_droplets)
- [cloud/AGENTS.md](../cloud/AGENTS.md) - Operator cleanup (uses aws-functions/)
- [vars/AGENTS.md](../vars/AGENTS.md) - Shared helpers (openshiftDiscovery)

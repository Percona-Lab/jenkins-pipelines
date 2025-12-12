# AGENTS.md - Cloud Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## TL;DR

**What**: K8s operator testing (PGO, PXCO, PSMDBO), EKS/OpenShift/GKE cluster lifecycle, orphaned resource cleanup
**Where**: Jenkins `cloud` | `https://cloud.cd.percona.com` | Jobs: `*-operator-*`, `pgo_*`, `pxco_*`
**Critical**: Clusters cost ~$200/day - ALWAYS cleanup in `post` blocks
**Key Helpers**: `eksctlCreateCluster()`, `openshiftClusterDestroy()`, `withCredentials([aws(...)])`

## Quick Reference

| Key | Value |
|-----|-------|
| Jenkins Instance | `cloud` |
| URL | https://cloud.cd.percona.com |
| Job Patterns | `*-operator-*`, `pgo_*`, `pxco_*`, `psmdbo_*` |
| Default Region | `us-east-2` |
| Groovy Files | 45+ |

## Scope

Cloud and Kubernetes operator pipelines: EKS/OpenShift cluster provisioning, operator testing (PG, PXC, PSMDB), multi-cloud infrastructure management, and orphaned resource cleanup.

## Key Files

| File | Purpose |
|------|---------|
| `jenkins/pgo_eks.groovy` | PostgreSQL Operator on EKS |
| `jenkins/pxco_eks.groovy` | PXC Operator on EKS |
| `jenkins/psmdbo_eks.groovy` | MongoDB Operator on EKS |
| `jenkins/pgo_openshift.groovy` | PGO on OpenShift |
| `jenkins/weekly_pgo.groovy` | Weekly PGO tests |
| `aws-functions/orphaned_eks_clusters.py` | EKS cleanup |
| `aws-functions/orphaned_openshift_instances.py` | OpenShift cleanup |

## Key Jenkins Jobs

| Job Name | Purpose |
|----------|---------|
| `pgo_eks` | PostgreSQL Operator on EKS |
| `pxco_eks` | PXC Operator on EKS |
| `psmdbo_eks` | MongoDB Operator on EKS |
| `*_openshift` | OpenShift variants |
| `*_gke` | GKE variants |

## Platform Matrix

| Platform | Primary Use | Cost |
|----------|-------------|------|
| EKS | Main K8s testing | ~$200/day |
| OpenShift (ROSA) | Enterprise testing | ~$300/day |
| GKE | Secondary testing | ~$150/day |
| Minikube | Local testing | Free |

## AWS Details

```groovy
// EKS cluster lifecycle
environment {
    AWS_DEFAULT_REGION = 'us-east-2'
    CLUSTER_NAME = "test-${BUILD_NUMBER}"
}

// Always cleanup!
post {
    always {
        sh "eksctl delete cluster --name ${CLUSTER_NAME} --region us-east-2"
        deleteDir()
    }
}
```

## Common Pitfalls

| Mistake | Why Wrong | Fix |
|---------|-----------|-----|
| No cluster cleanup | $200+/day leaked | Always in `post.always` |
| Wrong credential wrapper | Multi-cloud auth fails | `withAWS` vs `withGCP` |
| Missing timeout | Jobs hang forever | Add `timeout(time: 2, unit: 'HOURS')` |
| Hardcoded cluster names | Name collisions | Use `${BUILD_NUMBER}` suffix |

## Agent Workflow

1. **Check cluster params**: `~/bin/jenkins params cloud/pgo_eks`
2. **Credential wrapping**: Different per cloud (`withAWS`, `withGCP`)
3. **Cost awareness**: Use spot instances, set timeouts
4. **Cleanup**: `eksctlDeleteCluster()` or `openshiftClusterDestroy()` in `post`

## PR Review Checklist

- [ ] Cluster cleanup in `post.always`
- [ ] `timeout()` wrapper on cluster operations
- [ ] Correct credential wrapper for target cloud
- [ ] No hardcoded cluster names
- [ ] Resource tagging for cleanup automation

## Change Impact

| Change | Impact | Notify |
|--------|--------|--------|
| Cluster defaults | Cost changes | Platform team |
| Operator version | Test matrix | Operator teams |
| Cleanup logic | Cost optimization | All teams |

## Orphaned Resource Cleanup

Lambda functions in `aws-functions/`:
- `orphaned_eks_clusters.py` - Find/delete stale EKS clusters
- `orphaned_openshift_instances.py` - OpenShift instance cleanup
- `orphaned_cloudformation.py` - Stale CloudFormation stacks
- `orphaned_vpcs.py` - Remove orphaned VPCs
- `orphaned_oidc.py` - OIDC provider cleanup

## Validation

```bash
# Groovy lint
groovy -e "new GroovyShell().parse(new File('cloud/jenkins/pgo_eks.groovy'))"

# Python helpers
python3 -m py_compile cloud/aws-functions/orphaned_eks_clusters.py
```

## Jenkins CLI

```bash
~/bin/jenkins job cloud list                     # All jobs
~/bin/jenkins job cloud list | grep operator     # Operator jobs
~/bin/jenkins params cloud/<job>                 # Parameters
~/bin/jenkins build cloud/<job> -p KEY=val       # Build
```

## Related

- `pmm/AGENTS.md` - PMM pipelines (PMM HA on EKS lives in `pmm/v3/`)
- `IaC/` - CloudFormation for Jenkins infrastructure
- `vars/AGENTS.md` - Shared helpers (eksctl*, openshift*)

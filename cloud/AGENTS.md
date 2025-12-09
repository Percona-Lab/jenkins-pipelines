# AGENTS.md - Cloud Pipelines

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

Cloud and Kubernetes operator pipelines: EKS/OpenShift cluster provisioning, operator testing (PG, PXC, PSMDB), multi-cloud infrastructure management, and orphaned resource cleanup.

## Key Files

- `pgo_v1_operator_eks.groovy` - PostgreSQL Operator on EKS
- `psmdbo_openshift.groovy` - MongoDB Operator on OpenShift
- `pxc_operator_eks.groovy` - PXC Operator on EKS
- AWS Lambda functions for cluster cleanup

## Product-Specific Patterns

### Kubernetes Platforms

Supported platforms:
- **AWS EKS** - Primary Kubernetes platform
- **OpenShift (ROSA)** - Red Hat OpenShift on AWS
- **GKE/AKS** - Secondary platforms for specific tests

### Operator Testing Matrix

```groovy
// Operator versions tested
operators: ['pgo', 'pxc', 'psmdb']
platforms: ['eks', 'openshift', 'gke']
versions: ['1.x', '2.x']  // Operator versions
```

### Cluster Lifecycle

```groovy
// EKS cluster provisioning
eksctlCreateCluster(params)
// ... run tests ...
eksctlDeleteCluster(params)  // Always cleanup

// OpenShift cluster provisioning
openshiftClusterCreate(params)
// ... run tests ...
openshiftClusterDestroy(params)
```

## Agent Workflow

1. **Inspect cluster jobs:** `~/bin/jenkins job cloud config pgo_v1_operator_eks --yaml` to understand cluster parameters, operator versions, and test suites.
2. **Cluster cleanup is critical:** Always ensure clusters are destroyed in `post` blocks. Use `orphaned_eks_clusters.py` and `orphaned_openshift_instances.py` for leak detection.
3. **Cost awareness:** EKS/OpenShift clusters are expensive. Use `spot` instances where possible and set appropriate timeouts.
4. **Multi-cloud credentials:** Different clouds require different credential wrappers (`withAWS`, `withGCP`, `withAzure`).
5. **Operator version matrix:** Test new operator releases against multiple K8s versions before promoting.

## Validation & Testing

- **Groovy lint:** `groovy -e "new GroovyShell().parse(new File('cloud/pgo_v1_operator_eks.groovy'))"`
- **Python helpers:** `python3 -m py_compile cloud/aws/orphaned_eks_clusters.py`
- **Local K8s:** Use minikube or kind for local testing before pushing to cloud clusters.
- **Cost check:** Review AWS Cost Explorer after major test runs to catch resource leaks.

## Credentials & Parameters

- **Credentials:**
  - `aws-jenkins` - AWS access for EKS
  - `openshift-*` - OpenShift cluster credentials
  - `gcp-*`, `azure-*` - Multi-cloud access
- **Key parameters:** `CLUSTER_NAME`, `K8S_VERSION`, `OPERATOR_VERSION`, `PLATFORM`, `REGION`.
- **Cleanup:** Set `CLEANUP=true` as default; only disable for debugging.

# Jenkins

Instance: `cloud` | URL: `https://cloud.cd.percona.com`

## CLI
```bash
~/bin/jenkins job cloud list                        # All jobs
~/bin/jenkins job cloud list | grep operator        # Operator jobs
~/bin/jenkins params cloud/<job>                    # Parameters
~/bin/jenkins build cloud/<job> -p KEY=val          # Build
```

## API
```bash
# Auth: API token from Jenkins → User → Configure → API Token
curl -su "USER:TOKEN" "https://cloud.cd.percona.com/api/json?tree=jobs%5Bname%5D" | jq -r '.jobs[].name | select(contains("operator"))'
```

## Job Patterns
`*-operator-*`, `pgo_*`, `pxco_*`, `psmdbo_*`, `eks-*`, `openshift-*`

## Credentials
`aws-jenkins` (AWS/EKS), `openshift-*` (OpenShift), `gcp-*`, `azure-*` (multi-cloud). Always use `withCredentials`.

## Orphaned Resource Cleanup

Lambda functions in `cloud/aws/` detect and clean up orphaned resources:
- `orphaned_eks_clusters.py` - Find EKS clusters without recent activity
- `orphaned_openshift_instances.py` - Find OpenShift instances to terminate

## Related Jobs

- Operator-specific jobs (pgo, pxc, psmdb)
- PMM HA on EKS (`pmm3-ha-eks`) - in pmm/v3/
- Infrastructure cleanup jobs

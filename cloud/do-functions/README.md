# DigitalOcean cleanup functions

The `cmd/clusters.go` function removes DOKS clusters according to the
`delete-cluster-after-hours:<hours>` tag. Cluster age is calculated from the API
`created_at` timestamp. All deletion candidates must be tagged with
`cloud-team` or assigned to the `cloud-team` project. Clusters without the TTL
tag are deleted immediately when they are in that cleanup scope. Clusters that
match neither condition are preserved. Cluster deletion uses the dangerous
endpoint to include its associated resources.

The same execution immediately removes these orphaned resources when they are
tagged with `cloud-team` or assigned to the `cloud-team` project:

- regional load balancers without backend Droplets;
- volumes not attached to a Droplet;
- volume snapshots whose source volume no longer exists.

DigitalOcean load balancers do not support resource tags. Their `tag` field is
a dynamic Droplet backend selector, so a load balancer configured with it is
considered attached; load balancers are scoped through project membership.
Volume snapshots cannot be assigned directly to a project, so they must carry
the `cloud-team` tag to be eligible for deletion.

Clusters, volumes, and volume snapshots tagged with `skip-cluster-deletion` are
always preserved, regardless of their age or TTL configuration.

Invalid or non-positive TTL values are treated as configuration errors and the
cluster is preserved.

## Configuration

Set one of these namespace environment variables before deployment:

- `DIGITALOCEAN_ACCESS_TOKEN` (preferred) or `DIGITALOCEAN_TOKEN`: token with
  read and delete scopes for Kubernetes, load balancers, block storage, and
  snapshots, projects, plus `tag:read`.
- `DRY_RUN`: set to `true` to list deletion candidates without deleting them.
  It defaults to `false`.

The exported handler is `Main`. Configure the deployed function to run hourly.

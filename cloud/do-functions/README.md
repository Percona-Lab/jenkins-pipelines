# DigitalOcean cleanup functions

The `cmd/clusters.go` function removes DOKS clusters according to the
`delete-cluster-after-hours:<hours>` tag. Cluster age is calculated from the API
`created_at` timestamp. Clusters without this tag are deleted only when tagged
with `cloud-team` or assigned to the `cloud-team` project. Clusters that
match neither condition are preserved. Cluster deletion uses the dangerous
endpoint to include associated resources.

The same execution also removes orphaned resources older than one hour:

- regional load balancers without backend Droplets;
- volumes not attached to a Droplet;
- volume snapshots whose source volume no longer exists.

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

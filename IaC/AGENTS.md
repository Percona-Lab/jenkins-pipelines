# AGENTS.md - Infrastructure as Code (Hetzner Branch)

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

Infrastructure-as-code (IaC) for Jenkins infrastructure on Hetzner Cloud. Includes CloudFormation templates, Lambda utilities, Jenkins master bootstrap scripts (`init.groovy.d/`), Hetzner Cloud integration scripts, and per-product `.cd/` stacks.

## Key Directories

- `init.groovy.d/` – Jenkins master initialization scripts
- `*.cd/` – Product-specific CloudFormation directories with Hetzner integration

Each `.cd/` directory contains:
- `JenkinsStack.yml` – CloudFormation template
- `init.groovy.d/cloud.groovy` – Cloud provider configuration
- `init.groovy.d/htz.cloud.groovy` – Hetzner Cloud integration (Hetzner branch)

Product instances: `pmm.cd`, `ps80.cd`, `psmdb.cd`, `pxb.cd`, `pxc.cd`, `ps57.cd`, `ps3.cd`, `cloud.cd`, `rel.cd`, `pg.cd`

Note: No `cdk/` directory exists. CloudFormation YAML templates are used.

## Key Files

- `pmm.cd/JenkinsStack.yml` – PMM Jenkins stack
- `ps80.cd/JenkinsStack.yml` – Percona Server 8.0 stack
- `psmdb.cd/JenkinsStack.yml` – Percona Server MongoDB stack
- `pg.cd/JenkinsStack.yml` – PostgreSQL (PPG) stack
- `LambdaEC2Cleanup.yml` – EC2 cleanup Lambda
- `LambdaVolumeCleanup.yml` – EBS volume cleanup Lambda
- `init.groovy.d/*.groovy` – Jenkins bootstrap scripts
- `init.groovy.d/cloud.groovy` – Cloud provider config
- `init.groovy.d/htz.cloud.groovy` – Hetzner Cloud integration (10 instances, Hetzner-specific)

## Hetzner Cloud Integration

### Hetzner-Specific Files

Each `.cd/` directory has additional Hetzner configuration:
```
pmm.cd/init.groovy.d/htz.cloud.groovy
ps80.cd/init.groovy.d/htz.cloud.groovy
psmdb.cd/init.groovy.d/htz.cloud.groovy
pg.cd/init.groovy.d/htz.cloud.groovy
... (10 total)
```

These files configure:
- Hetzner Cloud API integration
- Hetzner-specific agent labels
- Hetzner Object Storage credentials
- Cloud provider selection logic

## Agent Workflow

1. **Confirm scope:** Identify affected Jenkins master (`pmm.cd`, `ps80.cd`, etc.)
2. **Work in dev/staging:** Never deploy directly to production
3. **Validate templates:** Run `aws cloudformation validate-template`
4. **Track parameters:** Keep CloudFormation and Jenkins parameters in sync
5. **Security review:** IAM roles, security groups, credentials require review

## Validation & Testing

```bash
# CloudFormation validation
aws cloudformation validate-template --template-body file://pmm.cd/JenkinsStack.yml

# CloudFormation lint
cfn-lint IaC/pmm.cd/JenkinsStack.yml

# Groovy init script validation
groovy -e "new GroovyShell().parse(new File('IaC/init.groovy.d/cloud.groovy'))"
groovy -e "new GroovyShell().parse(new File('IaC/pmm.cd/init.groovy.d/htz.cloud.groovy'))"
```

## Boundaries

### Never do
- Modify `init.groovy.d/` without security review
- Deploy CloudFormation directly to production
- Change Lambda IAM roles without coordination
- Modify `.cd/` stacks without checking downstream jobs

### Ask first
- Adding new VPCs, subnets, or Jenkins instances
- Introducing new Lambda functions
- Adjusting Jenkins master sizing

## Credentials

- `aws-iac-admin` – Primary IaC deployment credential
- Per-product IAM users/roles in each `.cd/` directory
- Hetzner API credentials for htz.cloud.groovy integration

## Jenkins CLI

IaC manages Jenkins infrastructure. Use these to inspect running configurations:

```bash
# jenkins CLI
~/bin/jenkins list-instances                       # All instances
~/bin/jenkins admin <inst> info                    # System info

# curl (see root AGENTS.md for auth)
curl -su "USER:TOKEN" "https://pmm.cd.percona.com/api/json" | jq '.nodeDescription'

# Find Hetzner cloud configs
ls IaC/*.cd/init.groovy.d/htz.cloud.groovy         # All Hetzner configs
```

## Hetzner Branch Specifics

### Additional Files:
- 10 `htz.cloud.groovy` files (one per `.cd/` directory)
- Hetzner Cloud API integration
- Dual cloud provider support (AWS + Hetzner)

### Configuration:
- Cloud provider selection via environment/parameters
- Hetzner Object Storage for artifacts
- Hetzner-specific agent labels in init scripts

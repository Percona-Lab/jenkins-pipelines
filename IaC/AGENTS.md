# AGENTS.md - Infrastructure as Code

Extends: [../AGENTS.md](../AGENTS.md)

## Scope

Infrastructure-as-code (IaC) for Jenkins infrastructure: CloudFormation templates, AWS CDK apps, Lambda utilities, Jenkins master bootstrap scripts (`init.groovy.d/`), and per-product `.cd/` stacks.

## Key Directories

- `init.groovy.d/` – Jenkins master initialization scripts
- `*.cd/` – Product-specific CloudFormation directories (`pmm.cd`, `ps80.cd`, `psmdb.cd`, `pxb.cd`, `pxc.cd`, `ps57.cd`, `ps3.cd`, `cloud.cd`, `rel.cd`, `pg.cd`)

Note: No `cdk/` directory exists. CloudFormation YAML templates are used for infrastructure definitions.

## Key Files

- `pmm.cd/JenkinsStack.yml` – PMM Jenkins CloudFormation stack
- `ps80.cd/JenkinsStack.yml` – Percona Server 8.0 Jenkins stack
- `psmdb.cd/JenkinsStack.yml` – Percona Server MongoDB Jenkins stack
- `pg.cd/JenkinsStack.yml` – PostgreSQL (PPG) Jenkins stack
- `LambdaEC2Cleanup.yml` – Lambda function for EC2 cleanup
- `LambdaVolumeCleanup.yml` – Lambda function for EBS volume cleanup
- `init.groovy.d/*.groovy` – Jenkins configuration bootstrap scripts
- `init.groovy.d/cloud.groovy` – Cloud provider configuration (master branch)

Note: Hetzner branch includes additional `init.groovy.d/htz.cloud.groovy` files in each `.cd/` directory for Hetzner Cloud integration.

## Agent Workflow

1. **Confirm scope:** Identify which Jenkins master or AWS account is affected (`pmm.cd`, `ps80.cd`, etc.) before editing.
2. **Work in dev/staging:** Never deploy directly to production accounts; synthesize templates locally and coordinate rollouts with SRE.
3. **Validate templates:** Run `aws cloudformation validate-template` or `cdk synth` before opening a PR.
4. **Track parameters:** Keep parameter names/types in sync between CloudFormation, CDK, and Jenkins jobs that consume them.
5. **Security review:** Any change touching IAM roles, security groups, or credentials must be reviewed by security/SRE.

## Validation & Testing

```bash
# CloudFormation validation
aws cloudformation validate-template --template-body file://pmm.cd/JenkinsStack.yml

# CloudFormation lint (if cfn-lint installed)
cfn-lint IaC/pmm.cd/JenkinsStack.yml

# Lambda validation
# Lambdas are defined in CloudFormation templates
# Test by creating test stacks in sandbox account

# Groovy init script validation
groovy -e "new GroovyShell().parse(new File('IaC/init.groovy.d/cloud.groovy'))"
```

Use AWS named profiles or environment variables that point to sandbox accounts when running these commands locally.

## Boundaries

### Never do

- Modify `init.groovy.d/` without a security review.
- Deploy CloudFormation changes straight to production without testing in a dev account.
- Change Lambda IAM roles/policies without coordination.
- Modify existing `.cd/` stacks without verifying downstream job dependencies.

### Ask first

- Adding new VPCs, subnets, or Jenkins instances.
- Introducing new Lambda functions or scheduled tasks.
- Adjusting Jenkins master sizing or AMIs.

## Credentials

- `aws-iac-admin` – Primary credential for IaC deployments.
- Per-product IAM users/roles defined inside each `.cd/` directory. Use `withCredentials([aws(...)])` wrappers and never paste secrets.

## Additional Notes

- Keep CloudFormation parameter defaults, CDK context, and Jenkins job parameters aligned to avoid drift.
- Document any bootstrap or migration steps in the PR and update `AGENTS.md` when the process changes.

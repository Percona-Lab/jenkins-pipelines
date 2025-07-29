# OpenShift Jenkins Jobs

This directory contains Jenkins job definitions for OpenShift cluster management.

## Files

- `openshift-cluster-create-fixed.yaml` - YAML definition for creating OpenShift clusters
- `openshift-cluster-destroy.yaml` - YAML definition for destroying OpenShift clusters
- `openshift-cluster-create.xml` - Generated XML for create job (from YAML)
- `openshift-cluster-destroy.xml` - Generated XML for destroy job (from YAML)

## Usage

### Build Jobs (YAML to XML)
```bash
just openshift-build
```

### Deploy Jobs to Jenkins
```bash
just openshift-deploy
```

### Delete Jobs from Jenkins
```bash
just openshift-delete
```

### Full Workflow (Build + Deploy)
```bash
just openshift-full
```

## Prerequisites

1. Docker with `jenkins-job-builder:latest` image
2. `.env` file with Jenkins credentials:
   ```
   JENKINS_URL=https://your-jenkins-url
   JENKINS_USER=your-username
   JENKINS_TOKEN=your-api-token
   ```

## Job Details

### openshift-cluster-create
Creates OpenShift clusters with supported versions:
- 4.19.0
- 4.18.0
- 4.17.0
- 4.16.20
- 4.16.15
- 4.15.38

### openshift-cluster-destroy
Destroys OpenShift clusters and cleans up workspace

## Required Jenkins Credentials
- `jenkins-openshift-aws` - AWS credentials for OpenShift operations
- `openshift-pull-secret` - Red Hat registry access token
- `openshift-ssh-key` - SSH key for cluster access
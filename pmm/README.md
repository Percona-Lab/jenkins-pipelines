# PMM Jenkins Guide

## Important directories in repo
- [CloudFormation for PMM Jenkins](https://github.com/Percona-Lab/jenkins-pipelines/tree/master/IaC/pmm.cd)
- [PMM pipelines](https://github.com/Percona-Lab/jenkins-pipelines/tree/master/pmm)
- [Jenkins variable (named helpers in this doc)](https://github.com/Percona-Lab/jenkins-pipelines/tree/master/vars)

## Agents
We build the custom image for agents and you can find Packer and Ansible files in [pmm-infra](https://github.com/percona/pmm-infra/tree/main/packer) repo.

You can use the following tags for agents:
- `agent-amd64` - short-lived agent based on Amazon Linux 2. After completing one task, the agent dies and a new one is started
- `agent-arm64` - the same as previous but arm64 arch
- `cli` - long-lived agents for tasks that do not require interaction with the file system. For example: run awc-cli command. These agents used ARM64 arch and read-only filesystem.

## Tips

### You can run Python scripts as part of pipeline
We have [runPython](https://github.com/Percona-Lab/jenkins-pipelines/blob/master/vars/runPython.groovy) helper and if you want to use it:

1. Create your Python script in [resources](https://github.com/Percona-Lab/jenkins-pipelines/tree/master/resources) directory.
2. Use `runPython('your_script_name')` or `runPython('your_script_name', 'your_scripts_args')`.

## The Zen of Jenkinsfile
1. All pipelines should be stored in this repo.
2. If your bash code is longer than 10 lines maybe worth using Python

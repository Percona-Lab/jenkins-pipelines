# PMM Jenkins

## Important parts of this repository

- [CloudFormation for PMM Jenkins](https://github.com/Percona-Lab/jenkins-pipelines/tree/master/IaC/pmm.cd)
- [PMM pipelines](https://github.com/Percona-Lab/jenkins-pipelines/tree/master/pmm)
- [Jenkins variable (named helpers in this doc)](https://github.com/Percona-Lab/jenkins-pipelines/tree/master/vars)

## Agents

We build custom images for agents. You can find Packer and Ansible files in [pmm-infra](https://github.com/percona/pmm-infra/tree/main/packer).

You can use the following tags for agents:

- `agent-amd64` - short-lived agent based on Amazon Linux 2. After completing one task, the agent dies and a new one is started
- `agent-arm64` - short-lived agent based on arm64 arch
- `cli` - long-lived agents for tasks that do require little or no interaction with the file system. For example: run awc-cli command. These agents use arm64 arch.

## Tips

### You can run Python scripts as part of a pipeline

We have [runPython](https://github.com/Percona-Lab/jenkins-pipelines/blob/master/vars/runPython.groovy) helper and if you want to use it:

1. Create your Python script in [resources](https://github.com/Percona-Lab/jenkins-pipelines/tree/master/resources) directory.
2. Use `runPython('your_script_name')` or `runPython('your_script_name', 'your_scripts_args')`.

## Pipeline best practice

We encourage you to conslult a section of Jenkins [documentation](https://www.jenkins.io/doc/book/pipeline/) dedicated to the pipelines. It provides a lot of insights, best practices and examples. Otherwise, our own reccomendations are:

1. All pipelines should be persisted to this repository. If a pipeline is not part of it, it may get lost on the next Jenkins restart.
2. Make sure the pipeline has an `options` block defining the number of pipeline builds Jenkins should keep, which will prevent from disk memory exhaustion. Example:

```Jenkinsfile
pipeline {
  ...
  options {
    buildDiscarder(logRotator(numToKeepStr: '10'))
  }
  ...
}
```

3. The pipelines running on long-lived agents should also clean up the working directory either on start or during the `post cleanup` stage:

```Jenkinsfile
pipeline {
  ...
  post {
    cleanup {
      ...
      deleteDir()
    }
  }
}
```

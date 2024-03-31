library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

List all_actions = [
    "install",
    "pro_to_pro",
    "not_pro_to_pro",
    "downgrade",
]

List nodes_to_test = []
if (params.node_to_test == "all") {
    nodes_to_test = ps80ProOperatingSystems()
} else {
    nodes_to_test = [params.node_to_test]
}

void runNodeBuild(String node_to_test) {
    build(
        job: 'package-testing-ps-8.0-pro',
        parameters: [
            string(name: "cur_action_to_test", value: params.cur_action_to_test),
            string(name: "node_to_test", value: node_to_test),
            string(name: "install_repo", value: params.install_repo),
            string(name: "product_to_test", value: product_to_test),
            string(name: "pro_test", value: params.pro_test),
            string(name: "check_warnings", value: params.check_warnings),
            string(name: "install_mysql_shell", value: params.install_mysql_shell),
            string(name: "TESTING_BRANCH", value: params.TESTING_BRANCH),
            string(name: "TESTING_GIT_ACCOUNT", value: params.TESTING_GIT_ACCOUNT),
        ],
        propagate: true,
        wait: true
    )
}

pipeline {
  agent {
    label 'docker'
  }
  environment {
    PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
  }
  parameters {
    choice(
        name: "cur_action_to_test",
        choices: ["all"] + all_actions,
        description: "Action to test on the product"
    )
    choice(
        name: 'node_to_test',
        description: 'For what platform (OS) need to test',
        choices: ["all"] + ps80ProOperatingSystems()
    )
    choice(
      name: "install_repo",
      choices: ["testing", "main"],
      description: "Repo to use in install test"
    )
    choice(
      name: "product_to_test",
      choices: ["ps80"],
      description: "Product for which the packages will be tested"
    )
    choice(
      name: 'pro_test',
      description: 'Mark whether the test is for pro packages or not',
      choices: ["yes",]
    )
    choice(
      name: "check_warnings",
      choices: ["yes", "no"],
      description: "check warning in client_test"
    )
    choice(
      name: "install_mysql_shell",
      choices: ["no","yes"],
      description: "install and check mysql-shell for ps80"
    )
    string(
      defaultValue: 'master',
      description: 'Branch for package-testing repository',
      name: 'TESTING_BRANCH'
    )
    string(
      defaultValue: 'Percona-QA',
      description: 'Git account for package-testing repository',
      name: 'TESTING_GIT_ACCOUNT'
    )
  }
  options {
    withCredentials(moleculePdpsJenkinsCreds())
    disableConcurrentBuilds()
  }
    stages {
        stage("Prepare") {
            steps {
                script {
                    currentBuild.displayName = "#${BUILD_NUMBER}-${cur_action_to_test}-${params.install_repo}"
                    currentBuild.description = "action: ${params.action_to_test} node: ${params.node_to_test}"
                }
            }
        }

        stage("Run parallel") {
            parallel {
                stage("Oracle Linux 9") {
                    when {
                        expression {
                            nodes_to_test.contains("oracle-9")
                        }
                    }
                    steps {
                        runNodeBuild("oracle-9")
                    }
                }
                stage("Debian Bookworm") {
                    when {
                        expression {
                            nodes_to_test.contains("debian-12")
                        }
                    }
                    steps {
                        runNodeBuild("debian-12")
                    }
                }
                stage("Ubuntu Jammy") {
                    when {
                        expression {
                            nodes_to_test.contains("ubuntu-jammy")
                        }
                    }
                    steps {
                        runNodeBuild("ubuntu-jammy")
                    }
                }
            }
        }
    }
}

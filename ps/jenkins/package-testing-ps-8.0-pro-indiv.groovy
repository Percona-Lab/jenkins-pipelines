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

pipeline {
  agent {
    label 'min-bookworm-x64'
  }
  environment {
    PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
    MOLECULE_DIR = "molecule/ps-80-pro/${cur_action_to_test}";
  }
  parameters {
    choice(
        name: "cur_action_to_test",
        choices: all_actions,
        description: "Action to test on the product"
    )
    choice(
        name: 'node_to_test',
        description: 'For what platform (OS) need to test',
        choices: ps80ProOperatingSystems()
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
    choice(
        name: 'DESTROY_ENV',
        description: 'Destroy VM after tests',
        choices: [
            'yes',
            'no'
        ]
    )
  }
  options {
    withCredentials(moleculePdpsJenkinsCreds())
  }
  stages {
    stage('Set build name'){
      steps {
        script {
          currentBuild.displayName = "${env.BUILD_NUMBER}--${env.node_to_test}-${env.cur_action_to_test}"
          currentBuild.description = "${env.install_repo}-${env.TESTING_BRANCH}"
        }
      }
    }
    stage('Check version param and checkout') {
      steps {
        deleteDir()
        git poll: false, branch: TESTING_BRANCH, url: "https://github.com/${TESTING_GIT_ACCOUNT}/package-testing.git"
      }
    }
    stage ('Prepare') {
      steps {
        script {
          installMoleculeBookworm()
        }
      }
    }
    stage ('Create virtual machines') {
      steps {
        script{
          moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "create", env.node_to_test)
        }
      }
    }
    stage ('Run playbook for test') {
      steps {
        withCredentials([usernamePassword(credentialsId: 'PS_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
          script {
              moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "converge", env.node_to_test)
          }
        }
      }
    }
  }
  post {
    always {
    script {
        if (env.DESTROY_ENV == "yes") {
            moleculeExecuteActionWithScenario(env.MOLECULE_DIR, "destroy", env.node_to_test)
        }
      }
    }
  }
}

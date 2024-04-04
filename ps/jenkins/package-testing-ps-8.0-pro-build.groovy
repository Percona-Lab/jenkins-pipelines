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

List actions_to_test = []
if (params.cur_action_to_test == "all") {
    actions_to_test = all_actions
} else {
    actions_to_test = [params.cur_action_to_test]
}

def runMoleculeAction(String action, String os) {
    MOLECULE_DIR="molecule/ps-80-pro/"+action
    sh """
        echo "Run for ${action} on ${os} "
        . virtenv/bin/activate
        cd ${MOLECULE_DIR}
        cur_action_to_test=${action} molecule test -s ${os}
    """
}

def destroyMoleculeAction(String action, String os) {
    MOLECULE_DIR="molecule/ps-80-pro/"+action
    sh """
        echo "Destroy ${action} on ${os} "
        . virtenv/bin/activate
        cd ${MOLECULE_DIR}
        cur_action_to_test=${action} molecule destroy -s ${os}
    """
}


pipeline {
    agent {
        label 'min-centos-7-x64'
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
    }
    options {
        withCredentials(moleculePdpsJenkinsCreds())
    }
    stages {
        stage('Set build name'){
            steps {
                script {
                currentBuild.displayName = "${env.BUILD_NUMBER}-${env.cur_action_to_test}-${env.node_to_test}"
                currentBuild.description = "${env.install_repo}-${env.TESTING_BRANCH}"
                }
            }
        }
        stage('Checkout') {
            steps {
                deleteDir()
                git poll: false, branch: TESTING_BRANCH, url: "https://github.com/${TESTING_GIT_ACCOUNT}/package-testing.git"
            }
        }
        stage ('Prepare') {
            steps {
                script {
                installMolecule()
                }
            }
        }

        stage("Run parallel INSTALL and UPGRADE"){
            parallel{
                stage("INSTALL") {
                    when {
                        expression {
                            actions_to_test.contains("install")
                        }
                    }
                    steps {
                        withCredentials([usernamePassword(credentialsId: 'PS_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                            script{
                                runMoleculeAction("install", params.node_to_test)
                            }
                        }
                    }
                    post {
                        always {
                            script{
                                destroyMoleculeAction("install", params.node_to_test)
                            }
                        }
                    }
                }
                stage("PRO TO PRO") {
                    when {
                        allOf{
                            expression{actions_to_test.contains("pro_to_pro")}
                            expression{params.install_repo != "main"}                
                        }
                    }
                    steps {
                        withCredentials([usernamePassword(credentialsId: 'PS_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                            script {
                                runMoleculeAction("pro_to_pro", params.node_to_test)
                            }
                        }
                    }
                    post {
                        always {
                            script{
                                destroyMoleculeAction("pro_to_pro", params.node_to_test)
                            }
                        }
                    }
                }
                stage("NOT PRO TO PRO") {
                    when {
                        allOf{
                            expression{actions_to_test.contains("not_pro_to_pro")}
                            expression{params.install_repo != "main"}                
                        }
                    }
                    steps{
                        withCredentials([usernamePassword(credentialsId: 'PS_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                            script{
                                runMoleculeAction("not_pro_to_pro", params.node_to_test)
                            }
                        }
                    }
                    post {
                        always {
                            script{
                                destroyMoleculeAction("not_pro_to_pro", params.node_to_test)
                            }
                        }
                    }
                }
                stage("DOWNGRADE") {
                    when {
                        allOf{
                            expression{actions_to_test.contains("downgrade")}
                            expression{params.install_repo != "main"}                
                        }
                    }
                    steps{
                        withCredentials([usernamePassword(credentialsId: 'PS_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
                            script{
                                runMoleculeAction("downgrade", params.node_to_test)
                            }
                        }
                    }
                    post {
                        always {
                            script{
                                destroyMoleculeAction("downgrade", params.node_to_test)
                            }
                        }
                    }
                }
            }
        }
    }
}

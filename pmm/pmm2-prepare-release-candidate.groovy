library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runSubmodulesRewind(String GIT_BRANCH) {
    rewindSubmodule = build job: 'pmm2-rewind-submodules-fb', propagate: false, parameters: [
        string(name: 'GIT_BRANCH', value: GIT_BRANCH)
    ]
}

void runPMM2ServerAutobuild(String GIT_BRANCH, String DESTINATION) {
    pmm2Server = build job: 'pmm2-server-autobuild', parameters: [
        string(name: 'GIT_BRANCH', value: GIT_BRANCH),
        string(name: 'DESTINATION', value: DESTINATION)
    ]
}

void runPMM2ClientAutobuild(String GIT_BRANCH, String DESTINATION) {
    pmm2Client = build job: 'pmm2-client-autobuilds', parameters: [
        string(name: 'GIT_BRANCH', value: GIT_BRANCH),
        string(name: 'DESTINATION', value: DESTINATION)
    ]
}

void runPMM2AMIBuild(String GIT_BRANCH, String RELEASE_CANDIDATE) {
    pmm2AMI = build job: 'pmm2-ami', parameters: [
        string(name: 'GIT_BRANCH', value: GIT_BRANCH),
        string(name: 'RELEASE_CANDIDATE', value: RELEASE_CANDIDATE)
    ]
}

void runPMM2OVFBuild(String GIT_BRANCH, String RELEASE_CANDIDATE) {
    pmm2OVF = build job: 'pmm2-ovf', parameters: [
        string(name: 'GIT_BRANCH', value: GIT_BRANCH),
        string(name: 'RELEASE_CANDIDATE', value: RELEASE_CANDIDATE)
    ]
}

void setupReleaseBranches(String VERSION, String GIT_BRANCH) {

    def pmm_submodules = [
        "pmm",
        "pmm-admin",
        "pmm-agent",
        "pmm-managed",
        "qan-api2",
        "pmm-update",
        "pmm-server",
        "grafana-dashboards",
        "pmm-api-tests",
        "pmm-ui-tests",
        "pmm-qa"
    ]
    def dependent_submodules = [
        "mysqld_exporter",
        "grafana",
        "dbaas-controller",
        "node_exporter",
        "mongodb_exporter",
        "postgres_exporter",
        "clickhouse_exporter",
        "proxysql_exporter",
        "rds_exporter",
        "azure_metrics_exporter",
        "percona-toolkit"
    ]
    sh '''
        git branch \${RELEASE_BRANCH}
        git checkout \${RELEASE_BRANCH}
    '''
    pmm_submodules.each { submodule ->
        println "Preparing Release branch for Submodule: $submodule"
        createBranch(submodule, 'release-' + VERSION)
    }
    dependent_submodules.each { submodule ->
        println "Preparing Release branch for Submodule: $submodule"
        createBranch(submodule, 'pmm-' + VERSION)
    }
    withCredentials([sshUserPrivateKey(credentialsId: 'GitHub SSH Key', keyFileVariable: 'SSHKEY', passphraseVariable: '', usernameVariable: '')]) {
        sh """
            set -o errexit
            set -o xtrace

            echo "/usr/bin/ssh -i "${SSHKEY}" -o StrictHostKeyChecking=no \\\"\\\$@\\\"" > github-ssh.sh
            chmod 755 github-ssh.sh
            export GIT_SSH=\$(pwd -P)/github-ssh.sh
            git commit -a -m "Prepare Release Branch Submodules"
            git branch
            git push --set-upstream origin \${RELEASE_BRANCH}
        """
    }
}

void createBranch(String SUBMODULE, String BRANCH)
{
    withCredentials([sshUserPrivateKey(credentialsId: 'GitHub SSH Key', keyFileVariable: 'SSHKEY', passphraseVariable: '', usernameVariable: '')]) {
        sh """
            set -o errexit
            set -o xtrace

            echo "/usr/bin/ssh -i "${SSHKEY}" -o StrictHostKeyChecking=no \\\"\\\$@\\\"" > github-ssh.sh
            chmod 755 github-ssh.sh
            export GIT_SSH=\$(pwd -P)/github-ssh.sh
            export SUBMODULE=${SUBMODULE}
            export BRANCH=${BRANCH}
            export submodule_url=\$(git config --file=.gitmodules submodule.\${SUBMODULE}.url)
            export submodule_branch=\$(git config --file=.gitmodules submodule.\${SUBMODULE}.branch)
            export ssh_submodule_url=\$(echo \$submodule_url | sed "s^https://github.com/^git@github.com:^g")
            git config --file=.gitmodules submodule.\${SUBMODULE}.branch release-${VERSION}
            cd /tmp/
            export submodule_branch_exist=\$(git ls-remote --heads \${submodule_url} \${BRANCH} | wc -l)
            if [[ \${submodule_branch_exist} != 1 ]]; then
                git clone --branch \${submodule_branch} \${ssh_submodule_url}
                cd \${SUBMODULE}
                git branch \${BRANCH}
                git checkout \${BRANCH}
                git push --set-upstream origin \${BRANCH}
            fi
        """
    }
}

pipeline {
    agent {
        label 'micro-amazon'
    }
    parameters {
        string(
            defaultValue: 'PMM-2.0',
            description: 'Prepare Submodules from pmm-submodules branch',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '2.17.0',
            description: 'Release Version',
            name: 'VERSION')
    }
    stages {
        stage('Check if Release Branch Exist') {
            steps {
                deleteDir()
                script {
                    env.RELEASE_BRANCH = 'release-' + VERSION
                    env.EXIST = sh (
                        script: 'git ls-remote --heads https://github.com/Percona-Lab/pmm-submodules release-\${VERSION} | wc -l',
                        returnStdout: true
                    ).trim()
                }
            }
        }
        stage('Checkout Submodules and Prepare for creating branches') {
            when {
                expression { env.EXIST.toInteger() == 0 }
            }
            steps {
                git branch: GIT_BRANCH, credentialsId: 'GitHub SSH Key', poll: false, url: 'git@github.com:Percona-Lab/pmm-submodules'
                sh """
                    sudo yum install -y git wget jq
                    git config --global user.email "dev-services@percona.com"
                    git config --global user.name "PMM Jenkins"
                """
                setupReleaseBranches(VERSION, GIT_BRANCH)
            }
        }
        stage('Rewind Release Submodule') {
            steps {
                runSubmodulesRewind(RELEASE_BRANCH)
            }
        }
        stage('Autobuilds RC for Server & Client') {
            parallel {
                stage('Start PMM2 Server Autobuild') {
                    steps {
                        runPMM2ServerAutobuild(RELEASE_BRANCH, 'testing')
                    }
                }
                stage('Start PMM2 Client Autobuild') {
                    steps {
                        runPMM2ClientAutobuild(RELEASE_BRANCH, 'testing')
                    }
                }
            }
        }
        stage('Autobuilds RC for OVF & AMI') {
            parallel {
                stage('Start AMI Build for RC') {
                    steps {
                        runPMM2AMIBuild('master', 'yes')
                    }
                }
                stage('Start OVF Build for RC') {
                    steps {
                        runPMM2OVFBuild('master', 'yes')
                    }
                }
            }
        }
    }
    post {
        always {
            sh 'sudo rm -r /tmp/'
            deleteDir()
        }
    }
}

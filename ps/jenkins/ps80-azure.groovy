pipeline {
    agent {
        label 'min-centos-7-x64'
    }
    parameters {
        string(
            defaultValue: 'marketplace',
            description: 'Tag/Branch for percona-images repository',
            name: 'GIT_BRANCH')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    stages {
        stage('Prepare') {
            steps {
                slackSend botUser: true, channel: '#releases-ci', color: '#FFFF00', message: "PS80-AZURE: build started - ${BUILD_URL}"
                git poll: true, branch: "marketplace", url: "https://github.com/Percona-Lab/percona-images.git"
                sh """
                    sudo yum -y install unzip
                    echo -e "[azure-cli]
name=Azure CLI
baseurl=https://packages.microsoft.com/yumrepos/azure-cli
enabled=1
gpgcheck=1
gpgkey=https://packages.microsoft.com/keys/microsoft.asc" | sudo tee /etc/yum.repos.d/azure-cli.repo
                    sudo rpm --import https://packages.microsoft.com/keys/microsoft.asc
                    sudo yum -y install azure-cli
                    make clean
                    make deps
                """
            }
        }

        stage('Build Image Dev-Latest') {
            steps {
                    sh """
                        set -o pipefail
                        ~/bin/packer build \
                        -only azure-arm -color=false packer/mysql80.json \
                            | tee build.log
                    """
                    sh 'tail build.log | grep us-east-1 | cut -d " " -f 2 > IMAGE'
                    stash includes: 'IMAGE', name: 'IMAGE'
                    archiveArtifacts 'IMAGE'
            }
        }
    }

    post {
        always {
            deleteDir()
        }
        success {
            script {
                unstash 'IMAGE'
                def IMAGE = sh(returnStdout: true, script: "cat IMAGE").trim()
                slackSend botUser: true, channel: '#releases-ci', color: '#00FF00', message: "PS80-AZURE: build finished - ${IMAGE}"
            }
        }
        failure {
            slackSend botUser: true, channel: '#releases-ci', color: '#FF0000', message: "PS80-AZURE: build failed"
        }
    }
}

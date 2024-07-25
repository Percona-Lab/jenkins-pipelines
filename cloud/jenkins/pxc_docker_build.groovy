
pipeline {
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-xtradb-cluster-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-xtradb-cluster-operator',
            description: 'percona-xtradb-cluster-operator repository',
            name: 'GIT_REPO')
    }
    agent {
         label 'docker'
    }
    environment {
        DOCKER_REPOSITORY_PASSPHRASE = credentials('DOCKER_REPOSITORY_PASSPHRASE')
        DOCKER_TAG = sh(script: "echo ${GIT_BRANCH} | sed -e 's^/^-^g; s^[.]^-^g;' | tr '[:upper:]' '[:lower:]'", , returnStdout: true).trim()
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }

    stages {
        stage('Prepare') {
            steps {
                git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                sh """
                    TRIVY_VERSION=\$(curl --silent 'https://api.github.com/repos/aquasecurity/trivy/releases/latest' | grep '"tag_name":' | tr -d '"' | sed -E 's/.*v(.+),.*/\\1/')
                    wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz
                    sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz -C /usr/local/bin/

                    if [ ! -f junit.tpl ]; then
                        wget --directory-prefix=/tmp https://raw.githubusercontent.com/aquasecurity/trivy/v\${TRIVY_VERSION}/contrib/junit.tpl
                    fi

                    # sudo is needed for better node recovery after compilation failure
                    # if building failed on compilation stage directory will have files owned by docker user
                    sudo sudo git config --global --add safe.directory '*'
                    sudo git reset --hard
                    sudo git clean -xdf
                    sudo rm -rf source
                    ./cloud/local/checkout $GIT_REPO $GIT_BRANCH
                """
                stash includes: "source/**", name: "sourceFILES"
            }
        }

        stage('Build and push docker image') {
            steps {
                retry(3) {
                    timeout(time: 30, unit: 'MINUTES') {
                        unstash "sourceFILES"
                        withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                            sh """
                                docker login -u '${USER}' -p '${PASS}'
                                docker buildx create --use
                                cd ./source/
                                DOCKER_DEFAULT_PLATFORM='linux/amd64,linux/arm64' ./e2e-tests/build
                                sudo rm -rf ./build
                                docker logout
                            """
                       }
                    }
                }
            }
        }

        stage('Check PXC docker image') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        IMAGE_NAME='percona-xtradb-cluster-operator'
                        TrivyLog="$WORKSPACE/trivy-hight-pxc.xml"

                        sg docker -c "
                            docker login -u '${USER}' -p '${PASS}'
                            /usr/local/bin/trivy -q --cache-dir /mnt/jenkins/trivy-${JOB_NAME}/ image --format template --template @/tmp/junit.tpl -o \$TrivyLog --timeout 5m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL perconalab/\$IMAGE_NAME:\${DOCKER_TAG}
                        "

                    """
                }
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: '*.pdf', allowEmptyArchive: true
            deleteDir()
        }
        unstable {
            slackSend channel: '#cloud-dev-ci', color: '#F6F930', message: "Building of PXC operator docker images unstable. Please check the log ${BUILD_URL}"
        }
        failure {
            slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "Building of PXC operator docker image failed. Please check the log ${BUILD_URL}"
        }
    }
}

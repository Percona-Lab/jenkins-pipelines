String getTrivyCveSummary(reportPath) {
    if (!fileExists(reportPath)) {
        return ''
    }

    def report = readFile(reportPath)
    int highCount = report.split('\\[HIGH\\]', -1).size() - 1
    int criticalCount = report.split('\\[CRITICAL\\]', -1).size() - 1

    if (highCount == 0 && criticalCount == 0) {
        return ''
    }

    return "\n*CVE*\n*CRITICAL:* `${criticalCount}`\n*HIGH:* `${highCount}`"
}

pipeline {
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-server-mysql-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-server-mysql-operator',
            description: 'percona-server-mysql-operator repository',
            name: 'GIT_REPO')
    }
    agent {
         label 'docker' 
    }
    environment {
        DOCKER_REPOSITORY_PASSPHRASE = credentials('DOCKER_REPOSITORY_PASSPHRASE')
        DOCKER_TAG = sh(script: "echo ${GIT_BRANCH} | sed -e 's^/^-^g; s^[.]^-^g;' | tr '[:upper:]' '[:lower:]'", , returnStdout: true).trim()
        TRIVY_VERSION = '0.69.3'
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
                    wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz
                    sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz -C /usr/local/bin/
                    rm -f trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz

                    if [ ! -f /tmp/junit.tpl ]; then
                        wget --directory-prefix=/tmp https://raw.githubusercontent.com/aquasecurity/trivy/v\${TRIVY_VERSION}/contrib/junit.tpl
                    fi

                    # sudo is needed for better node recovery after compilation failure
                    # if building failed on compilation stage directory will have files owned by docker user
                    sudo git config --global --add safe.directory '*'
                    sudo git reset --hard
                    sudo git clean -xdf
                    sudo rm -rf source
                    ./cloud/local/checkout $GIT_REPO $GIT_BRANCH
                """
                stash includes: "source/**", name: "sourceFILES"
            }
        }

        stage('Build PS operator docker image') {
            steps {
                retry(3) {
                    timeout(time: 30, unit: 'MINUTES') {
                        unstash "sourceFILES"
                        withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                            sh """
                                echo "\$PASS" | docker login -u "\$USER" --password-stdin
                                docker buildx create --use
                                cd ./source/
                                DOCKER_DEFAULT_PLATFORM='linux/amd64,linux/arm64' ./e2e-tests/build
                                docker logout
                                sudo rm -rf ./build
                            """
                        }
                    }
                }
            }
        }

        stage('Check PSMO docker image') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        IMAGE_NAME='percona-server-mysql-operator'
                        TrivyLog="$WORKSPACE/trivy-ps.xml"

                        sg docker -c "
                            echo "\$PASS" | docker login -u "\$USER" --password-stdin
                            /usr/local/bin/trivy -q --cache-dir /mnt/jenkins/trivy-${JOB_NAME}/ image --format template --template @/tmp/junit.tpl -o \$TrivyLog --timeout 5m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL perconalab/\$IMAGE_NAME:\${DOCKER_TAG}
                            docker logout
                        "

                    """
                }
            }
            post {
                always {
                    junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-ps.xml"
                }
            }
        }
    }

    post {
        always {
        }
        unstable {
            script {
                def trivySummary = getTrivyCveSummary('trivy-ps.xml')
                slackSend channel: '#cloud-dev-ci', color: '#F6F930', message: "Building of PSM operator docker images unstable.${trivySummary} Please check the log ${BUILD_URL}"
            }
        }
        failure {
            script {
                def trivySummary = getTrivyCveSummary('trivy-ps.xml')
                slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "Building of PSM operator docker image failed.${trivySummary} Please check the log ${BUILD_URL}"
            }
        }
        cleanup {
            deleteDir()
        }
    }
}

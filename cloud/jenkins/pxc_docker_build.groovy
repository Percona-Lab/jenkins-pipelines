void checkImageForCVE(String IMAGE_SUFFIX){
    withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'),string(credentialsId: 'SYSDIG-API-KEY', variable: 'SYSDIG_API_KEY')]) {
        sh """
            IMAGE_SUFFIX=\$(echo ${IMAGE_SUFFIX} | sed 's^/^-^g; s^[.]^-^g;' | tr '[:upper:]' '[:lower:]')
            IMAGE_NAME='percona-xtradb-cluster-operator'
            docker run -v \$(pwd):/tmp/pgo --rm quay.io/sysdig/secure-inline-scan:2 perconalab/\$IMAGE_NAME:\${IMAGE_SUFFIX} --sysdig-token '${SYSDIG_API_KEY}' --sysdig-url https://us2.app.sysdig.com -r /tmp/pgo
        """
    }
}

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

        stage('Build docker image') {
            steps {
                unstash "sourceFILES"
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh '''
                        cd ./source/
                        DOCKER_PUSH=0 ./e2e-tests/build
                        sudo rm -rf ./build
                    '''
                }
            }
        }

        stage('Push docker image to dockerhub') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'), file(credentialsId: 'DOCKER_REPO_KEY', variable: 'docker_key')]) {
                    sh '''
                        sg docker -c "
                            mkdir -p /home/ec2-user/.docker/trust/private
                            cp "${docker_key}" ~/.docker/trust/private/

                            docker login -u '${USER}' -p '${PASS}'
                            export DOCKER_CONTENT_TRUST_REPOSITORY_PASSPHRASE="${DOCKER_REPOSITORY_PASSPHRASE}"
                            docker trust sign perconalab/percona-xtradb-cluster-operator:${DOCKER_TAG}
                            docker push perconalab/percona-xtradb-cluster-operator:${DOCKER_TAG}
                            docker logout
                        "
                    '''
                }
            }
        }
        stage('Check PXC docker image') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        IMAGE_NAME='percona-xtradb-cluster-operator'
                        TrivyLog="$WORKSPACE/trivy-hight-pxc.xml"
                        wget https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/junit.tpl

                        sg docker -c "
                            docker login -u '${USER}' -p '${PASS}'
                            /usr/local/bin/trivy -q --cache-dir /mnt/jenkins/trivy-${JOB_NAME}/ image --format template --template @junit.tpl -o \$TrivyLog --timeout 5m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL perconalab/\$IMAGE_NAME:\${DOCKER_TAG}
                        "

                    """
                }
            }
        }
        stage('Check PXCO Docker images for CVE') {
            steps {
                checkImageForCVE('\$GIT_BRANCH')
            }
            post {
                always {
                    junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "*-pxc.xml"
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

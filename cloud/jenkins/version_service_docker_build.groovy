void checkImageForDocker(){
     withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            IMAGE=\$(cat IMG)
            IMAGE_TAG=\$(echo "\$IMAGE" | cut -d':' -f2)
            TrivyLog="$WORKSPACE/trivy-version-service-\${IMAGE_TAG}.xml"

            sg docker -c "
                docker login -u '${USER}' -p '${PASS}'
                /usr/local/bin/trivy -q --cache-dir /mnt/jenkins/trivy-${JOB_NAME}/ image --format template --template @/tmp/junit.tpl -o \$TrivyLog --timeout 40m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL \$IMAGE
            "
        """
    }
}

pipeline {
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for Percona-Lab/percona-version-service repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/Percona-Lab/percona-version-service',
            description: 'Percona-Lab/percona-version-service repository',
            name: 'GIT_REPO')
    }
    agent { label "docker" }

    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    stages {
        stage('Prepare') {
            steps {
                git poll: true, branch: "${GIT_BRANCH}", url: 'https://github.com/Percona-Lab/percona-version-service'
                sh """
                    TRIVY_VERSION=\$(curl --silent 'https://api.github.com/repos/aquasecurity/trivy/releases/latest' | grep '"tag_name":' | tr -d '"' | sed -E 's/.*v(.+),.*/\\1/')
                    wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz
                    sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz -C /usr/local/bin/

                    if [ ! -f junit.tpl ]; then
                        wget --directory-prefix=/tmp https://raw.githubusercontent.com/aquasecurity/trivy/v\${TRIVY_VERSION}/contrib/junit.tpl
                    fi

                    wget 'https://github.com/FiloSottile/mkcert/releases/download/v1.4.3/mkcert-v1.4.3-linux-amd64'
                    sudo install -m 0755 -D mkcert-v1.4.3-linux-amd64 /usr/local/bin/mkcert
                    /usr/local/bin/mkcert -install

                    # sudo is needed for better node recovery after compilation failure
                    # if building failed on compilation stage directory will have files owned by docker user
                    sudo git config --global --add safe.directory /mnt/jenkins/workspace/build-version-service-image
                    sudo git reset --hard
                    sudo git clean -xdf

                    sudo rm -rf ./source ./checkout
                    wget https://raw.githubusercontent.com/Percona-Lab/jenkins-pipelines/master/cloud/local/checkout
                    install -m 0755 -D ./checkout cloud/local/checkout
                    ./cloud/local/checkout $GIT_REPO $GIT_BRANCH
                """
                //stash includes: "source/**", name: "sourceFILES"
            }
        }
        stage('Build version-service docker images') {
            steps {
                //unstash "sourceFILES"
                echo 'Build version-service docker images'
                retry(3) {
                    sh '''
                        export PATH=$PATH:/usr/local/go/bin:~/go/bin

                        export GIT_BRANCH=$(git rev-parse --abbrev-ref HEAD | sed -e 's^/^-^g; s^[.]^-^g;' | tr '[:upper:]' '[:lower:]')
                        export GIT_COMMIT=$(git rev-parse --short HEAD)
                        export IMG=perconalab/version-service:$GIT_BRANCH-$GIT_COMMIT

                        cd ./source
                        make docker-build

                        echo $IMG > ../IMG
                    '''
                    stash includes: 'IMG', name: 'IMG'
                }
            }
        }
        stage('Push version-service image to Docker registry') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'), string(credentialsId: 'DOCKER_REPOSITORY_PASSPHRASE', variable: 'DOCKER_REPOSITORY_PASSPHRASE'), file(credentialsId: 'DOCKER_REPO_KEY', variable: 'docker_key')]) {
                    sh '''
                        export IMG=\$(cat IMG)
                        sg docker -c "
                            if [ ! -d ~/.docker/trust/private ]; then
                                mkdir -p /home/ec2-user/.docker/trust/private
                                cp "${docker_key}" ~/.docker/trust/private/
                            fi
                            docker login -u '${USER}' -p '${PASS}'
                            docker push \$IMG
                            if [[ "\$IMG" == *"perconalab/version-service:main-"* ]]; then
                                export IMG_LATEST="perconalab/version-service:main-latest"
                                docker tag \$IMG \\$IMG_LATEST
                                docker push \\$IMG_LATEST
                            fi
                            docker logout
                        "
                    '''
                }
            }
        }
        stage('Trivy docker image check') {
            steps {
                checkImageForDocker()
            }
            post {
                always {
                    junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "trivy-version-service-*.xml"
                }
            }
        }
    }
    post {
        always {
            sh '''
                sudo docker rmi -f \$(sudo docker images -q | uniq) || true
                sudo rm -rf ${WORKSPACE}/*
            '''
            deleteDir()
        }
        success {
            script {
                unstash 'IMG'
                def IMG = sh(returnStdout: true, script: "cat IMG").trim()
                slackSend channel: '#version-service-monitoring', color: '#00FF00', message: "New version-service image is published - ${IMG}"
            }
        }
        failure {
            slackSend channel: '#version-service-monitoring', color: '#FF0000', message: "Building of version-service image failed. Please check the log ${BUILD_URL}"
        }
        unstable {
            slackSend channel: '#version-service-monitoring', color: '#F6F930', message: "Building of version-service docker images unstable. Please check the log ${BUILD_URL}"
        }
    }
}

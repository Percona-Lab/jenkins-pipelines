void checkImageForCVE(String IMAGE_SUFFIX){
    def report_file=''
    try {
        withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'),string(credentialsId: 'SYSDIG-API-KEY', variable: 'SYSDIG_API_KEY')]) {
            sh """
                IMAGE_SUFFIX=\$(echo ${IMAGE_SUFFIX} | sed 's^/^-^g; s^[.]^-^g;' | tr '[:upper:]' '[:lower:]')
                IMAGE_NAME='percona-postgresql-operator'
                docker run -v \$(pwd):/tmp/pgo --rm quay.io/sysdig/secure-inline-scan:2 perconalab/\$IMAGE_NAME:\${IMAGE_SUFFIX} --sysdig-token '${SYSDIG_API_KEY}' --sysdig-url https://us2.app.sysdig.com -r /tmp/pgo
            """
        }
    } catch (error) {
        echo "${IMAGE_SUFFIX} has some CVE error(s)."
        currentBuild.result = 'FAILURE'
    }
}

void checkImageForDocker(String IMAGE_SUFFIX){
     withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
        sh """
            IMAGE_SUFFIX=\$(echo ${IMAGE_SUFFIX} | sed 's^/^-^g; s^[.]^-^g;' | tr '[:upper:]' '[:lower:]')
            IMAGE_NAME='percona-postgresql-operator'
            TrityHightLog="$WORKSPACE/trivy-hight-\$IMAGE_NAME-${IMAGE_SUFFIX}.log"
            TrityCriticaltLog="$WORKSPACE/trivy-critical-\$IMAGE_NAME-${IMAGE_SUFFIX}.log"

            sg docker -c "
                docker login -u '${USER}' -p '${PASS}'
                /usr/local/bin/trivy -q --cache-dir /mnt/jenkins/trivy-${JOB_NAME}/ image -o \$TrityHightLog --timeout 10m0s --ignore-unfixed --exit-code 0 --severity HIGH perconalab/\$IMAGE_NAME:\${IMAGE_SUFFIX}
                /usr/local/bin/trivy -q --cache-dir /mnt/jenkins/trivy-${JOB_NAME}/ image -o \$TrityCriticaltLog --timeout 10m0s --ignore-unfixed --exit-code 0 --severity CRITICAL perconalab/\$IMAGE_NAME:\${IMAGE_SUFFIX}
            "

            if [ ! -s \$TrityHightLog ]; then
                rm -rf \$TrityHightLog
            fi

            if [ ! -s \$TrityCriticaltLog ]; then
                rm -rf \$TrityCriticaltLog
            fi
        """
    }
}

pipeline {
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-postgresql-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-postgresql-operator',
            description: 'percona/percona-postgresql-operator repository',
            name: 'GIT_REPO')
    }
    agent {
         label 'docker'
    }
    environment {
        DOCKER_REPOSITORY_PASSPHRASE = credentials('DOCKER_REPOSITORY_PASSPHRASE')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }

    stages {
        stage('Prepare') {
            steps {
                withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_TOKEN')]) {
                    git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                    sh """
                        export GIT_REPO=\$(echo \${GIT_REPO} | sed "s#github.com#\${GITHUB_TOKEN}@github.com#g")
                        TRIVY_VERSION=\$(curl --silent 'https://api.github.com/repos/aquasecurity/trivy/releases/latest' | grep '"tag_name":' | tr -d '"' | sed -E 's/.*v(.+),.*/\\1/')
                        wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz
                        sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz -C /usr/local/bin/

                        # sudo is needed for better node recovery after compilation failure
                        # if building failed on compilation stage directory will have files owned by docker user
                        sudo git reset --hard
                        sudo git clean -xdf
                        sudo rm -rf source
                        ./cloud/local/checkout
                    """
                    stash includes: "cloud/**" , name: "checkout"
                    stash includes: "source/**", name: "sourceFILES"

                    sh '''
                        rm -rf cloud
                    '''
                }
            }
        }

        stage('Build and push PGO docker images') {
            steps {
                unstash "sourceFILES"
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'),file(credentialsId: 'DOCKER_REPO_KEY', variable: 'docker_key'),string(credentialsId: 'SYSDIG-API-KEY', variable: 'SYSDIG_API_KEY')]) {
                    sh """
                        cd ./source/
                        TAG_PREFIX=\$(echo $GIT_BRANCH | sed 's^/^-^g; s^[.]^-^g;' | tr '[:upper:]' '[:lower:]')
                        sg docker -c "
                            if [ ! -d ~/.docker/trust/private ]; then
                                mkdir -p /home/ec2-user/.docker/trust/private
                                cp "${docker_key}" ~/.docker/trust/private/
                            fi

                            docker login -u '${USER}' -p '${PASS}'

                            ./e2e-tests/build

                            export DOCKER_CONTENT_TRUST_REPOSITORY_PASSPHRASE="${DOCKER_REPOSITORY_PASSPHRASE}"
                            docker trust sign perconalab/percona-postgresql-operator:\$TAG_PREFIX-pgo-apiserver
                            docker trust sign perconalab/percona-postgresql-operator:\$TAG_PREFIX-pgo-event
                            docker trust sign perconalab/percona-postgresql-operator:\$TAG_PREFIX-pgo-rmdata
                            docker trust sign perconalab/percona-postgresql-operator:\$TAG_PREFIX-pgo-scheduler
                            docker trust sign perconalab/percona-postgresql-operator:\$TAG_PREFIX-postgres-operator
                            docker trust sign perconalab/percona-postgresql-operator:\$TAG_PREFIX-pgo-deployer

                            docker logout
                        "
                    """
                }
            }
        }
        stage('Check PGO Docker images') {
            steps {
                checkImageForDocker('\$GIT_BRANCH-pgo-apiserver')
                checkImageForDocker('\$GIT_BRANCH-pgo-event')
                checkImageForDocker('\$GIT_BRANCH-pgo-rmdata')
                checkImageForDocker('\$GIT_BRANCH-pgo-scheduler')
                checkImageForDocker('\$GIT_BRANCH-postgres-operator')
                checkImageForDocker('\$GIT_BRANCH-pgo-deployer')
                sh '''
                   CRITICAL=$(ls trivy-critical-*) || true
                   if [ -n "$CRITICAL" ]; then
                       exit 1
                   fi
                '''
            }
        }
        stage('Check PGO Docker images for CVE') {
            steps {
                checkImageForCVE('\$GIT_BRANCH-pgo-apiserver')
                checkImageForCVE('\$GIT_BRANCH-pgo-event')
                checkImageForCVE('\$GIT_BRANCH-pgo-rmdata')
                checkImageForCVE('\$GIT_BRANCH-pgo-scheduler')
                checkImageForCVE('\$GIT_BRANCH-postgres-operator')
                checkImageForCVE('\$GIT_BRANCH-pgo-deployer')
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: '*.log', allowEmptyArchive: true
            archiveArtifacts artifacts: '*.pdf', allowEmptyArchive: true
            sh '''
                sudo docker rmi -f \$(sudo docker images -q) || true
                sudo rm -rf ./source/build
            '''
            deleteDir()
        }
        failure {
            slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "Building of PGO images failed. Please check the log ${BUILD_URL}"
        }
    }
}

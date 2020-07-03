pipeline {
    environment {
        specName = 'Docker'
    }
    agent {
        label 'min-buster-x64'
    }
    parameters {
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for percona-docker repository',
            name: 'GIT_BRANCH'
        )
        string(
            defaultValue: 'https://github.com/percona/percona-docker.git',
            description: 'percona-docker repository',
            name: 'GIT_REPO'
        )
        choice(
            choices: 'percona-server\npercona-server-mongodb\npercona-distribution-postgresql\npercona-xtrabackup\npercona-toolkit\nproxysql\nhaproxy',
            description: 'Select docker for build',
            name: 'DOCKER_NAME'
        )
        string(
            description: 'Version of selected product',
            name: 'DOCKER_VERSION'
        )
        choice(
            choices: 'Dockerfile\nDockerfile.debug\nDockerfile.k8s',
            description: 'Extension of dockerfile to be built',
            name: 'DOCKER_FILE'
        )
        choice(
            choices: 'percona\nperconalab',
            description: "Organization push to",
            name: 'DOCKER_ORG'
        )
        booleanParam(
            defaultValue: false,
            description: 'Set true to set MAJOR tag to version selected',
            name: 'DOCKER_MAJOR_TAG'
        )
        choice(
            choices: 'push\nexport',
            description: 'Export will trigger docker save and push will push to the dockerhub',
            name: 'DOCKER_MODE'
        )     
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }

    stages {
        stage('Prepare') {
            steps {
                sh '''
                  rm -f docker_builder.sh
                  sudo rm -rf tmp
                  mkdir tmp
                  wget https://raw.githubusercontent.com/Sudokamikaze/jenkins-pipelines/ENG-879/docker/docker_builder.sh
                  chmod +x docker_builder.sh
                  sudo bash -x docker_builder.sh --builddir=$(pwd)/tmp --install_deps=1
                  sudo gpasswd -a $(whoami) docker
                  bash -x docker_builder.sh --builddir=$(pwd)/tmp --repo=${GIT_REPO} --branch=${GIT_BRANCH} --get_sources=1
                '''
            }
        }

        stage('Build Image') {
            steps {
                sh """
                    sg docker -c "
                        bash -x docker_builder.sh --builddir=\$(pwd)/tmp --build_container=1 --product=\${DOCKER_NAME} \
                            --organization=\${DOCKER_ORG} --version=\${DOCKER_VERSION} --dockerfile=\${DOCKER_FILE} \
                            --update_major=\${DOCKER_MAJOR_TAG}
                    "
                """
            }
        }

        stage('Test image') {
            steps {
                sh """
                    sg docker -c "
                        cd \${WORKSPACE}/tmp/percona-docker/test
                        bash run.sh \${DOCKER_ORG}/\${DOCKER_NAME}:\${DOCKER_VERSION}
                    "
                """
            }
        }

        stage('Export Image') {
            when {
                expression { params.DOCKER_MODE == 'export' }
            }
            steps {
                sh """
                    sg docker -c "
                        bash -x docker_builder.sh --builddir=\$(pwd)/tmp --export_container=1 --product=\${DOCKER_NAME} --organization=\${DOCKER_ORG} --version=\${DOCKER_VERSION}
                    "
                """
                archiveArtifacts artifacts: 'tarball/**', followSymlinks: false, onlyIfSuccessful: true
            }
        }

        stage('Push Image') {
            when {
                expression { params.DOCKER_MODE == 'push' }
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        sg docker -c "
                            docker login -u \${USER} -p \${PASS}
                            bash -x docker_builder.sh --builddir=\$(pwd)/tmp --release_container=1 --product=\${DOCKER_NAME} --organization=\${DOCKER_ORG} --version=\${DOCKER_VERSION} \
                                --update_major=\${DOCKER_MAJOR_TAG}
                        "
                    """
                }
            }
        }

        stage('Cleanup') {
            steps {
                sh """
                    sg docker -c "
                        bash -x docker_builder.sh --builddir=\$(pwd)/tmp --cleanup=1
                    "
                    rm -rf \${WORKSPACE}/*
                """
            }            
        }
    }

    post {
        always {
            deleteDir()
        }
    }
}

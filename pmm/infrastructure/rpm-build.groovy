pipeline {
    agent none
    parameters {
        string(
            defaultValue: 'PMM-2.0',
            description: 'Tag/Branch for pmm-submodules repository',
            name: 'SUBMODULES_GIT_BRANCH')
    }
    options {
        skipStagesAfterUnstable()
        buildDiscarder(logRotator(artifactNumToKeepStr: '10'))
    }
    stages {
        stage('Build rpmbuild image') {
            matrix {
                agent {
                    label "${AGENT}"
                }
                axes {
                    axis {
                        name 'AGENT'
                        values 'docker-farm', 'docker-farm-arm'
                    }
                }
                stages {
                    stage('Prepare') {
                        steps {
                            git poll: true,
                                branch: SUBMODULES_GIT_BRANCH,
                                url: 'https://github.com/Percona-Lab/pmm-submodules.git'
                        }
                    }
                    stage('Build') {
                        steps {
                            withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                                sh """
                                    sg docker -c "
                                        echo "${PASS}" | docker login -u "${USER}" --password-stdin
                                    "
                                """
                            }
                            sh """
                                cd build/rpmbuild-docker
                                docker build --pull --tag perconalab/rpmbuild:2 .
                                docker push perconalab/rpmbuild:2
                            """
                        }
                    }
                }
            }
        }
    }
}


library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/EvgeniyPatlan/jenkins-pipelines.git'
]) _

pipeline {
    environment {
        specName = 'Docker'
    }
    agent {
        label 'min-jessie-x64'
    }
    parameters {
        choice(
            choices: 'percona-server\npercona-server.56\npercona-server-mongodb\npercona-server-mongodb.32\npercona-server-mongodb.34\npercona-server-mongodb.36\nproxysql\npxc-56\npxc-57',
            description: 'Select docker for build',
            name: 'DOCKER_NAME')
        string(
            defaultValue: 'master',
            description: 'Tag/Branch for percona-docker repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-docker.git',
            description: 'percona-docker repository',
            name: 'GIT_REPO')
        choice(
            choices: 'percona-server\npercona-server-mongodb\nproxysql\npercona-xtradb-cluster',
            description: 'perconalab dockerhub repository',
            name: 'DOCKER_REPO')
        string(
            defaultValue: 'dev-latest',
            description: 'Tag for perconalab dockerhub repository',
            name: 'DOCKER_TAG')
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
                  wget https://raw.githubusercontent.com/EvgeniyPatlan/build_scripts/master/docker/docker_builder.sh
                  chmod +x docker_builder.sh
                  sudo rm -rf docker_build
                  mkdir docker_build
                  sudo bash -x docker_builder.sh --builddir=$(pwd)/docker_build --repo=${GIT_REPO} --build_docker=0 --save_docker=0 --docker_name=${DOCKER_NAME} --version=${PACKAGE} --clean_docker=0 --test_docker=0 --install_docker=1
                '''
            }
        }

        stage('Build Image') {
            steps {
                sh '''
                  sudo bash -x docker_builder.sh --builddir=$(pwd)/docker_build --repo=${GIT_REPO} --build_docker=1 --save_docker=0 --docker_name=${DOCKER_NAME} --version=${PACKAGE} --clean_docker=1 --test_docker=0 --install_docker=0 --auto=1
                '''
            }
        }
        
        stage('Save Image') {
            steps {
                sh '''
                  sudo bash -x docker_builder.sh --builddir=$(pwd)/docker_build --repo=${GIT_REPO} --build_docker=0 --save_docker=1 --docker_name=${DOCKER_NAME} --version=${PACKAGE} --clean_docker=0 --test_docker=0 --install_docker=0 --auto=1
                  TAR=$(ls $(pwd)/docker_build | grep tar)
                  sudo cp $(pwd)/docker_build/${TAR} ./ 
                '''
                archiveArtifacts "*.tar.gz"
            }
        }
        
        stage('Test Image') {
            steps {
                sh '''
                  TAR=$(ls $(pwd)/docker_build | grep tar)
                  sudo bash -x docker_builder.sh --builddir=$(pwd)/docker_build --repo=${GIT_REPO} --build_docker=0 --save_docker=0 --docker_name=${DOCKER_NAME} --version=${PACKAGE} --clean_docker=1 --test_docker=1 --install_docker=0 --auto=1 --load_docker=$(pwd)/docker_build/${TAR}; \
                  sudo rm -rf $(pwd)/docker_build
                '''
            }
        }
        

        stage('Upload') {
            steps {
                sh """
                    sudo docker tag ${DOCKER_NAME} perconalab/${DOCKER_REPO}:${DOCKER_TAG}
                """
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        sudo docker login -u "${USER}" -p "${PASS}"
		        sudo docker push perconalab/${DOCKER_REPO}:${DOCKER_TAG}
                    """
                }
                sh """
                    sudo docker rm -f \$(sudo docker ps -aq) || true
                    sudo docker rmi -f \$(sudo docker images -q) || true
                """
            }
        }
    }

    post {
        always {
            deleteDir()
        }
        success {
            slackSend channel: '@evgeniy', color: '#00FF00', message: "[${specName}]: build finished"
        }
        failure {
            slackSend channel: '@evgeniy', color: '#FF0000', message: "[${specName}]: build failed"
        }
    }
}

void build(String IMAGE_POSTFIX){
    sh """
        set -e

        cd ./source/
        if [ "${IMAGE_POSTFIX}" == "orchestrator" ]; then
            docker build --no-cache --squash --progress plain \
                -t perconalab/percona-server-mysql-operator:${GIT_PD_BRANCH}-${IMAGE_POSTFIX} \
                -f ./orchestrator/Dockerfile ./orchestrator
        elif [ "${IMAGE_POSTFIX}" == "backup8.0" ]; then
            docker build --no-cache --squash --progress plain \
                -t perconalab/percona-server-mysql-operator:${GIT_PD_BRANCH}-${IMAGE_POSTFIX} \
                -f ./percona-xtrabackup-8.0/Dockerfile ./percona-xtrabackup-8.0
        elif [ "${IMAGE_POSTFIX}" == "router8.0" ]; then
            docker build --no-cache --squash --progress plain \
                -t perconalab/percona-server-mysql-operator:${GIT_PD_BRANCH}-${IMAGE_POSTFIX} \
                -f ./mysql-router/Dockerfile ./mysql-router
        elif [ "${IMAGE_POSTFIX}" == "psmysql8.0" ]; then
            docker build --no-cache --squash --progress plain \
                -t perconalab/percona-server-mysql-operator:${GIT_PD_BRANCH}-${IMAGE_POSTFIX} \
                -f ./percona-server-8.0/Dockerfile ./percona-server-8.0
        elif [ "${IMAGE_POSTFIX}" == "backup8.4" ]; then
            docker build --no-cache --squash --progress plain \
                -t perconalab/percona-server-mysql-operator:${GIT_PD_BRANCH}-${IMAGE_POSTFIX} \
                -f ./percona-xtrabackup-8.x/Dockerfile ./percona-xtrabackup-8.x
        elif [ "${IMAGE_POSTFIX}" == "psmysql8.4" ]; then
            docker build --no-cache --squash --progress plain \
                -t perconalab/percona-server-mysql-operator:${GIT_PD_BRANCH}-${IMAGE_POSTFIX} \
                -f ./percona-server-8.4/Dockerfile ./percona-server-8.4
        elif [ "${IMAGE_POSTFIX}" == "router8.4" ]; then
            docker build --no-cache --squash --progress plain \
                -t perconalab/percona-server-mysql-operator:${GIT_PD_BRANCH}-${IMAGE_POSTFIX} \
                -f ./mysql-router/Dockerfile.84 ./mysql-router
        elif [ "${IMAGE_POSTFIX}" == "toolkit" ]; then
            docker build --no-cache --squash --progress plain \
                -t perconalab/percona-server-mysql-operator:${GIT_PD_BRANCH}-${IMAGE_POSTFIX} \
                -f ./percona-toolkit/Dockerfile ./percona-toolkit
        elif [ "${IMAGE_POSTFIX}" == "haproxy" ]; then
            docker build --no-cache --squash --progress plain \
                -t perconalab/percona-server-mysql-operator:${GIT_PD_BRANCH}-${IMAGE_POSTFIX} \
                -f ./haproxy/Dockerfile ./haproxy
        fi
    """
}
void pushImageToDocker(String IMAGE_POSTFIX){
     withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'), file(credentialsId: 'DOCKER_REPO_KEY', variable: 'docker_key')]) {
        sh """
            sg docker -c '
              set -e

                if [ ! -d ~/.docker/trust/private ]; then
                    mkdir -p ~/.docker/trust/private
                    cp "${docker_key}" ~/.docker/trust/private/
                fi

                docker login -u '${USER}' -p '${PASS}'
                docker push perconalab/percona-server-mysql-operator:${GIT_PD_BRANCH}-${IMAGE_POSTFIX}
                docker logout
            '
            echo "perconalab/percona-server-mysql-operator:${GIT_PD_BRANCH}-${IMAGE_POSTFIX}" >> list-of-images.txt
        """
    }
}
void generateImageSummary(filePath) {
    def images = readFile(filePath).trim().split("\n")

    def report = "<h2>Image Summary Report</h2>\n"
    report += "<p><strong>Total Images:</strong> ${images.size()}</p>\n"
    report += "<ul>\n"

    images.each { image ->
        report += "<li>${image}</li>\n"
    }

    report += "</ul>\n"
    return report
}
pipeline {
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-docker repository',
            name: 'GIT_PD_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-docker',
            description: 'percona/percona-docker repository',
            name: 'GIT_PD_REPO')
    }
    agent {
         label 'docker-x64-min'
    }
    environment {
        PATH = "${WORKSPACE}/node_modules/.bin:$PATH" // Add local npm bin to PATH
        DOCKER_REPOSITORY_PASSPHRASE = credentials('DOCKER_REPOSITORY_PASSPHRASE')
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
                    export GIT_REPO=$GIT_PD_REPO
                    export GIT_BRANCH=$GIT_PD_BRANCH

                    # sudo is needed for better node recovery after compilation failure
                    # if building failed on compilation stage directory will have files owned by docker user
                    sudo git config --global --add safe.directory '*'
                    sudo git reset --hard
                    sudo git clean -xdf
                    sudo rm -rf source
                    ./cloud/local/checkout
                """
                stash includes: "cloud/**" , name: "checkout"
            }
        }
        stage('Build ps docker images') {
            steps {
                unstash "checkout"
                sh """
                   sudo mv ./source ./operator-source
                   export GIT_REPO=$GIT_PD_REPO
                   export GIT_BRANCH=$GIT_PD_BRANCH
                   ./cloud/local/checkout
                """
                retry(3) {
                    build('orchestrator')
                }
                retry(3) {
                    build('backup8.0')
                }
                retry(3) {
                    build('backup8.4')
                }
                retry(3) {
                    build('router8.0')
                }
                retry(3) {
                    build('router8.4')
                }
                retry(3) {
                    build('psmysql8.0')
                }
                retry(3) {
                    build('psmysql8.4')
                }
                retry(3) {
                    build('toolkit')
                }
                retry(3) {
                    build('haproxy')
                }
            }
        }
        stage('Push Images to Docker registry') {
            steps {
                pushImageToDocker('orchestrator')
                pushImageToDocker('backup8.0')
                pushImageToDocker('backup8.4')
                pushImageToDocker('router8.0')
                pushImageToDocker('router8.4')
                pushImageToDocker('psmysql8.0')
                pushImageToDocker('psmysql8.4')
                pushImageToDocker('toolkit')
                pushImageToDocker('haproxy')
            }
        }
    }
    post {
        always {
            script {
                def summary = generateImageSummary('list-of-images.txt')

                addSummary(icon: 'symbol-aperture-outline plugin-ionicons-api',
                    text: "<pre>${summary}</pre>"
                )
                // Also save as a file if needed
                 writeFile(file: 'image-summary.html', text: summary)
            }
            sh '''
                sudo docker rmi -f \$(sudo docker images -q) || true
            '''
            deleteDir()
        }
        unstable {
            slackSend channel: '#cloud-dev-ci', color: '#F6F930', message: "Building of PS docker images unstable. Please check the log ${BUILD_URL}"
        }
        failure {
            slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "Building of PS docker images failed. Please check the log ${BUILD_URL}"
        }
    }
}


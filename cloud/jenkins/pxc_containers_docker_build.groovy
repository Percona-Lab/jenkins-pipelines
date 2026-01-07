void build(String IMAGE_PREFIX){
    sh """
        set -e
        cd ./source/
        if [ ${IMAGE_PREFIX} = pxc8.0 ]; then
            docker build --no-cache --squash --build-arg PXC_REPO=release -t perconalab/percona-xtradb-cluster-operator:${GIT_PD_BRANCH}-${IMAGE_PREFIX} -f percona-xtradb-cluster-8.0/Dockerfile percona-xtradb-cluster-8.0
            docker build --build-arg DEBUG=1 --no-cache --squash --build-arg PXC_REPO=release -t perconalab/percona-xtradb-cluster-operator:${GIT_PD_BRANCH}-${IMAGE_PREFIX}-debug -f percona-xtradb-cluster-8.0/Dockerfile percona-xtradb-cluster-8.0
        elif [ ${IMAGE_PREFIX} = pxc8.4 ]; then
            docker build --no-cache --squash --build-arg PXC_REPO=release -t perconalab/percona-xtradb-cluster-operator:${GIT_PD_BRANCH}-${IMAGE_PREFIX} -f percona-xtradb-cluster-8.4/Dockerfile percona-xtradb-cluster-8.4
            docker build --build-arg DEBUG=1 --no-cache --squash --build-arg PXC_REPO=release -t perconalab/percona-xtradb-cluster-operator:${GIT_PD_BRANCH}-${IMAGE_PREFIX}-debug -f percona-xtradb-cluster-8.4/Dockerfile percona-xtradb-cluster-8.4
        elif [ ${IMAGE_PREFIX} = proxysql ]; then
            docker build --no-cache --squash -t perconalab/percona-xtradb-cluster-operator:${GIT_PD_BRANCH}-${IMAGE_PREFIX} -f proxysql/Dockerfile proxysql
        elif [ ${IMAGE_PREFIX} = pxc8.0-backup ]; then
            docker build --no-cache --squash --build-arg PXC_REPO=release --build-arg TOOLS_REPO=release --build-arg PXB_REPO=release --build-arg PS_REPO=release \
                -t perconalab/percona-xtradb-cluster-operator:${GIT_PD_BRANCH}-${IMAGE_PREFIX} -f percona-xtrabackup-8.0/Dockerfile percona-xtrabackup-8.0
        elif [ ${IMAGE_PREFIX} = pxc8.4-backup ]; then
            docker build --no-cache --squash --build-arg PXC_REPO=release --build-arg TOOLS_REPO=release --build-arg PXB_REPO=release --build-arg PS_REPO=release \
                -t perconalab/percona-xtradb-cluster-operator:${GIT_PD_BRANCH}-${IMAGE_PREFIX} -f percona-xtrabackup-8.x/Dockerfile percona-xtrabackup-8.x
        elif [ ${IMAGE_PREFIX} = haproxy ]; then
            docker build --no-cache --squash -t perconalab/percona-xtradb-cluster-operator:${GIT_PD_BRANCH}-${IMAGE_PREFIX} -f haproxy/Dockerfile haproxy
        fi
    """
}
void pushImageToDocker(String IMAGE_PREFIX){
     withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER'), file(credentialsId: 'DOCKER_REPO_KEY', variable: 'docker_key')]) {
        sh """
            IMAGE_PREFIX=${IMAGE_PREFIX}
            sg docker -c "
                set -e
                if [ ! -d ~/.docker/trust/private ]; then
                    mkdir -p ~/.docker/trust/private
                    cp "${docker_key}" ~/.docker/trust/private/
                fi

                docker login -u '${USER}' -p '${PASS}'
                docker push perconalab/percona-xtradb-cluster-operator:${GIT_PD_BRANCH}-${IMAGE_PREFIX}
                docker logout
            "
            echo "perconalab/percona-xtradb-cluster-operator:${GIT_PD_BRANCH}-${IMAGE_PREFIX}" >> list-of-images.txt
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
         label 'docker-x64'
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
                    # sudo is needed for better node recovery after compilation failure
                    # if building failed on compilation stage directory will have files owned by docker user
                    sudo git config --global --add safe.directory '*'
                    sudo git reset --hard
                    sudo git clean -xdf

                    sudo rm -rf source
                    export GIT_REPO=$GIT_PD_REPO
                    export GIT_BRANCH=$GIT_PD_BRANCH
                    sudo rm -rf source
                    ./cloud/local/checkout
                """
                stash includes: "cloud/**", name: "cloud"

                sh '''
                    rm -rf cloud
                '''
            }
        }
        stage('Build pxc docker images') {
            steps {
                retry(3) {
                    build('pxc8.0-backup')
                }
                retry(3) {
                    build('pxc8.4-backup')
                }
                retry(3) {
                    build('proxysql')
                }
                retry(3) {
                    build('pxc8.0')
                }
                retry(3) {
                    build('pxc8.4')
                }
                retry(3) {
                    build('haproxy')
                }
            }
        }
        stage('Push Images to Docker registry') {
            steps {
                pushImageToDocker('pxc8.0')
                pushImageToDocker('pxc8.4')
                pushImageToDocker('pxc8.0-debug')
                pushImageToDocker('pxc8.4-debug')
                pushImageToDocker('proxysql')
                pushImageToDocker('pxc8.0-backup')
                pushImageToDocker('pxc8.4-backup')
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
            slackSend channel: '#cloud-dev-ci', color: '#F6F930', message: "Building of PXC docker images unstable. Please check the log ${BUILD_URL}"
        }
        failure {
            slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "Building of PXC docker images failed. Please check the log ${BUILD_URL}"
        }
    }
}

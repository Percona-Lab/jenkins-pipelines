List selectedTargets() {
    def targets = []
    def suffix = params.BASE_OS == 'ubi8' ? '-ubi8' : ''

    if (params.BUILD_POSTGRES) {
        for (ver in ['18', '17', '16', '15', '14']) {
            targets << "postgres${ver}${suffix}"
        }
    }
    if (params.BUILD_PGBACKREST) {
        targets << 'pgbackrest'
    }
    if (params.BUILD_PGBOUNCER) {
        targets << 'pgbouncer'
    }
    if (params.BUILD_UPGRADE) {
        targets << "upgrade${suffix}"
    }

    if (params.BUILD_POSTGRES19_BETA && params.BASE_OS == 'el9') {
        targets << 'postgres19'
        if (!targets.contains('pgbackrest')) {
            targets << 'pgbackrest'
        }
        targets << 'pgbackrest19'
        targets << 'pgbouncer19'
    }
    return targets
}

List imagesForTarget(String target, String tag) {
    def repo = 'perconalab/percona-postgresql-operator'
    switch (target) {
        case 'postgres19':
            return ["${repo}:${tag}-postgres19-community", "${repo}:${tag}-ppg19-postgres"]
        case 'pgbackrest19':
            return ["${repo}:${tag}-pgbackrest19"]
        case 'pgbouncer19':
            return ["${repo}:${tag}-pgbouncer19"]
        default:
            return ["${repo}:${tag}-${target.replace('-ubi8', '')}-community"]
    }
}

String makeArgs(String tag) {
    def args = "TAG=${tag}"
    if (params.BASE_OS == 'ubi8') {
        args += ' BASE_IMAGE=redhat/ubi8-minimal'
    }
    return args
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
    agent {
        label 'docker-x64'
    }

    environment {
        DOCKER_REPOSITORY_PASSPHRASE = credentials('DOCKER_REPOSITORY_PASSPHRASE')
    }

    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-docker repository',
            name: 'GIT_PD_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-docker',
            description: 'percona/percona-docker repository',
            name: 'GIT_PD_REPO')
        choice(
            name: 'BASE_OS',
            choices: ['el9', 'ubi8'],
            description: 'Base image family. el9 publishes <branch>-*-community tags, ubi8 publishes <branch>-ubi8-*-community tags.')
        booleanParam(
            name: 'BUILD_POSTGRES',
            defaultValue: true,
            description: 'Build postgres 14-18 community images')
        booleanParam(
            name: 'BUILD_POSTGRES19_BETA',
            defaultValue: false,
            description: 'Build postgres 19 (BETA, PGDG testing repo) community image')
        booleanParam(
            name: 'BUILD_PGBACKREST',
            defaultValue: true,
            description: 'Build pgbackrest community image')
        booleanParam(
            name: 'BUILD_PGBOUNCER',
            defaultValue: true,
            description: 'Build pgbouncer community image')
        booleanParam(
            name: 'BUILD_UPGRADE',
            defaultValue: true,
            description: 'Build upgrade community image')
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
                """
                stash includes: "cloud/**", name: "cloud"
            }
        }

        stage('Checkout percona-docker') {
            steps {
                sh 'sudo rm -rf cloud'
                unstash "cloud"
                sh """
                    sudo rm -rf source
                    export GIT_REPO=$GIT_PD_REPO
                    export GIT_BRANCH=$GIT_PD_BRANCH
                    ./cloud/local/checkout
                """
            }
        }

        stage('Check generated Dockerfiles are in sync') {
            steps {
                sh """
                    cd ./source/postgresql-containers/community
                    ./sync.sh | tee \$WORKSPACE/sync-report.txt
                    if grep -q 'source changed' \$WORKSPACE/sync-report.txt; then
                        echo 'ERROR: generated community Dockerfiles are stale.'
                        echo 'Run ./sync.sh --apply in postgresql-containers/community and commit the result.'
                        exit 1
                    fi
                """
            }
        }

        stage('Build and push community docker images') {
            steps {
                script {
                    def targets = selectedTargets()

                    if (targets.isEmpty()) {
                        error 'No image selected to build'
                    }

                    def tag = params.GIT_PD_BRANCH.replaceAll('[/.]', '-').toLowerCase()
                    if (params.BASE_OS == 'ubi8') {
                        tag += '-ubi8'
                    }

                    retry(3) {
                        timeout(time: 120, unit: 'MINUTES') {
                            withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                                sh """
                                    cd ./source/postgresql-containers/community
                                    sg docker -c "
                                        set -e
                                        echo \$PASS | docker login -u \$USER --password-stdin
                                        docker buildx create --use
                                        make ${targets.join(' ')} ${makeArgs(tag)}
                                        docker logout
                                    "
                                """
                            }
                        }
                    }

                    def images = targets.collectMany { imagesForTarget(it, tag) }.unique()
                    writeFile(file: 'list-of-images.txt', text: images.join('\n') + '\n')
                }
            }
        }
    }

    post {
        always {
            script {
                if (fileExists('list-of-images.txt')) {
                    def summary = generateImageSummary('list-of-images.txt')

                    addSummary(icon: 'symbol-aperture-outline plugin-ionicons-api',
                        text: "<pre>${summary}</pre>"
                    )
                    writeFile(file: 'image-summary.html', text: summary)
                } else {
                    echo 'No list-of-images.txt file found - skipping summary generation'
                }
            }
            sh '''
                sudo docker buildx prune -af || true
                sudo docker rmi -f \$(sudo docker images -q) || true
            '''
            deleteDir()
        }
        failure {
            slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "Building of PG community docker images failed. Please check the log ${BUILD_URL}"
        }
    }
}

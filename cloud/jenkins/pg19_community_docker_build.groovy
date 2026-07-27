// Builds and pushes the PostgreSQL 19 (BETA, tech preview) image set from
// percona-docker postgresql-containers/community. PG 19 images are built from
// PGDG testing packages (ppg-19 does not exist yet); pgAudit and pgBackRest
// 2.59 are compiled from source inside the images.
//
// Published tags (REGISTRY defaults to perconalab/percona-postgresql-operator):
//   <tag>-postgres19-community  - postgres 19 + patroni + pgbackrest client
//   <tag>-ppg19-postgres        - same image under the versioned CR name
//   <tag>-pgbackrest19          - pgBackRest 2.59 overlay for the repo-host
//   <tag>-pgbouncer19           - pgbouncer under the versioned CR name
//
// This job builds ONLY PG 19 images. Images for PG 14-18 are the regular
// Percona ones built by pg_containers_docker_build.groovy from ppg packages.
// Delete this job once ppg-19 is released and 19 joins the regular loop.

List PG19_TARGETS = ['postgres19', 'pgbackrest19', 'pgbouncer19']

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
            return []
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
        string(
            defaultValue: 'perconalab/percona-postgresql-operator:main-pgbackrest-community',
            description: 'Existing community pgbackrest image used as the base for the pgbackrest19 (2.59) overlay',
            name: 'PGBACKREST_BASE_IMAGE')
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

        stage('Build and push PG 19 docker images') {
            steps {
                script {
                    def tag = params.GIT_PD_BRANCH.replaceAll('[/.]', '-').toLowerCase()

                    retry(3) {
                        timeout(time: 120, unit: 'MINUTES') {
                            withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                                sh """
                                    cd ./source/postgresql-containers/community
                                    sg docker -c "
                                        set -e
                                        echo \$PASS | docker login -u \$USER --password-stdin
                                        docker buildx create --use
                                        make ${PG19_TARGETS.join(' ')} TAG=${tag} PGBACKREST_IMAGE=${params.PGBACKREST_BASE_IMAGE}
                                        docker logout
                                    "
                                """
                            }
                        }
                    }

                    def images = PG19_TARGETS.collectMany { imagesForTarget(it, tag) }.unique()
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
            slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "Building of PG 19 community docker images failed. Please check the log ${BUILD_URL}"
        }
    }
}

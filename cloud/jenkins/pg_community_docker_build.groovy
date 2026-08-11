// Builds and pushes the community PostgreSQL image set from percona-docker
// postgresql-containers/community: the UBI9 images (postgres 14-18, pgbackrest,
// pgbouncer, upgrade), the UBI8 images (postgres 14-18, upgrade; pushed under
// TAG-ubi8 so they do not overwrite the UBI9 tags) and the PostgreSQL 19
// (BETA, tech preview) set.
//
// PG 19 images are built from PGDG testing packages (ppg-19 does not exist
// yet); pgAudit and pgBackRest 2.59 are compiled from source inside the
// images. The PG 19 Dockerfiles are hand-maintained (not sync.sh-generated)
// until ppg-19 exists — once it is released, 19 joins the regular loop and
// the pg19 stage here goes away.
//
// Before building anything the job verifies the generated Dockerfiles are in
// sync with their Percona sources (sync.sh): a build from stale Dockerfiles
// would publish images that do not match the sources. The community unit
// tests (pytest) run in percona-docker's GitHub CI on every PR.
//
// Published tags (REGISTRY defaults to perconalab/percona-postgresql-operator):
//   <tag>-postgres{14..18}-community        - UBI9 postgres + patroni
//   <tag>-pgbackrest-community              - UBI9 pgbackrest
//   <tag>-pgbouncer-community               - UBI9 pgbouncer
//   <tag>-upgrade-community                 - UBI9 pg_upgrade image
//   <tag>-ubi8-postgres{14..18}-community   - UBI8 postgres + patroni
//   <tag>-ubi8-upgrade-community            - UBI8 pg_upgrade image
//   <tag>-postgres19-community              - postgres 19 beta (PGDG testing)
//   <tag>-ppg19-postgres                    - same image under the versioned CR name
//   <tag>-pgbackrest19                      - pgBackRest 2.59 overlay for the repo-host
//   <tag>-pgbouncer19                       - pgbouncer under the versioned CR name
//
// pgbackrest and pgbouncer have no UBI8 variant (no upstream Dockerfile-ubi8)
// and PG 19 has no UBI8 variant (the PGDG testing repository for EL8 is empty).

String imageTag() {
    return params.GIT_PD_BRANCH.replaceAll('[/.]', '-').toLowerCase()
}

// Ask the community Makefile which image tags a build group pushes (same TAG
// arguments as the build stages), so the summary cannot drift from percona-docker.
// Returns '' when the revision being built predates the list-images targets:
// a reporting gap must not fail a run whose pushes succeeded.
String listImages(String target, String tagArg) {
    return withEnv(["LIST_TARGET=${target}", "LIST_TAG=${tagArg}"]) {
        sh(
            script: '''
                cd ./source/postgresql-containers/community
                if make -s -n "$LIST_TARGET" >/dev/null 2>&1; then
                    make -s "$LIST_TARGET" TAG="$LIST_TAG"
                else
                    echo "WARNING: $LIST_TARGET is not present on this percona-docker revision, skipping it in the summary" >&2
                fi
            ''',
            returnStdout: true
        ).trim()
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

void buildAndPush(String makeArgs) {
    retry(3) {
        timeout(time: 180, unit: 'MINUTES') {
            withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                sh """
                    cd ./source/postgresql-containers/community
                    sg docker -c "
                        set -e
                        echo \$PASS | docker login -u \$USER --password-stdin
                        docker buildx use community-builder 2>/dev/null || docker buildx create --use --name community-builder
                        make ${makeArgs}
                        docker logout
                    "
                """
            }
        }
    }
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
        booleanParam(
            defaultValue: true,
            description: 'Build and push the UBI9 image set: postgres 14-18, pgbackrest, pgbouncer, upgrade (make all)',
            name: 'BUILD_UBI9')
        booleanParam(
            defaultValue: true,
            description: 'Build and push the UBI8 image set: postgres 14-18, upgrade, under TAG-ubi8 (make all-ubi8)',
            name: 'BUILD_UBI8')
        booleanParam(
            defaultValue: true,
            description: 'Build and push the PostgreSQL 19 beta image set: postgres19, pgbackrest19, pgbouncer19 (make pg19)',
            name: 'BUILD_PG19')
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
                sh '''
                    cd ./source/postgresql-containers/community
                    # sync.sh always exits 0: drift is detected by applying it and
                    # diffing the work tree. No --force: unchanged sources are
                    # deliberately left alone.
                    ./sync.sh --apply
                    cd ../..
                    git checkout -- postgresql-containers/community/.source-hashes || true
                    if ! git diff --exit-code -- postgresql-containers/community/build/ \
                        || [ -n "$(git status --porcelain -- postgresql-containers/community/build/)" ]; then
                        echo "ERROR: community Dockerfiles are out of sync with their sources;"
                        echo "run './sync.sh --apply' in postgresql-containers/community and commit the result"
                        exit 1
                    fi
                '''
            }
        }

        stage('Build and push UBI9 images') {
            when { expression { params.BUILD_UBI9 } }
            steps {
                script {
                    buildAndPush("all TAG=${imageTag()}")
                }
            }
        }

        stage('Build and push UBI8 images') {
            when { expression { params.BUILD_UBI8 } }
            steps {
                script {
                    // all-ubi8 reuses the UBI9 tag names, so it gets its own TAG
                    buildAndPush("all-ubi8 TAG=${imageTag()}-ubi8")
                }
            }
        }

        stage('Build and push PG 19 images') {
            when { expression { params.BUILD_PG19 } }
            steps {
                script {
                    // pgbackrest19 layers on the community pgbackrest image: use the
                    // one pushed by the UBI9 stage of this run (or of a previous run
                    // when BUILD_UBI9 is unchecked)
                    buildAndPush("pg19 TAG=${imageTag()} PGBACKREST_IMAGE=perconalab/percona-postgresql-operator:${imageTag()}-pgbackrest-community")
                }
            }
        }

        stage('Image summary') {
            steps {
                script {
                    def tag = imageTag()
                    def images = []
                    if (params.BUILD_UBI9) {
                        images += listImages('list-images-all', tag).split('\n').toList()
                    }
                    if (params.BUILD_UBI8) {
                        images += listImages('list-images-all-ubi8', "${tag}-ubi8").split('\n').toList()
                    }
                    if (params.BUILD_PG19) {
                        images += listImages('list-images-pg19', tag).split('\n').toList()
                    }
                    images = images.findAll { it }
                    if (images) {
                        writeFile(file: 'list-of-images.txt', text: images.unique().join('\n') + '\n')
                    }
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
            slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "Building of community PG docker images failed. Please check the log ${BUILD_URL}"
        }
    }
}

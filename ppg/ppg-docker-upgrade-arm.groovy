library changelog: false, identifier: "lib@hetzner", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
    }
    parameters {
        choice(name: 'CLOUD', choices: [ 'Hetzner','AWS' ], description: 'Cloud infra for build')
        choice(name: 'PPG_REPO', choices: ['testing','release','experimental'], description: 'Percona-release repo')
        string(name: 'IMAGE_TAG', defaultValue: '18.3-17.9-16.13-15.17-14.22-1', description: 'Image tag in the form <MAJ.MIN>-<MAJ.MIN>-...-<BUILD_NUMBER> (e.g. 18.3-17.9-16.13-15.17-14.22-1). The leading MAJ is used as PG_MAJOR build arg; the major-only manifest tag (e.g. 18-17-16-15-14) is derived from this.')
        choice(name: 'TARGET_REPO', choices: ['PerconaLab','DockerHub'], description: 'Target repo for the regular upgrade docker image (custom image always goes to PerconaLab). Use DockerHub for release only')
        choice(name: 'IMAGE_TYPE', choices: ['both','regular','custom'], description: 'Which upgrade image(s) to build: regular only, custom only, or both (default)')
        booleanParam(name: 'BUILD_UBI9', defaultValue: true, description: 'Build UBI9 image')
        booleanParam(name: 'BUILD_UBI8', defaultValue: true, description: 'Build UBI8 image (regular only, no custom variant)')
    }
    options {
        disableConcurrentBuilds()
    }
    stages {
        stage('Set build name'){
            steps {
                script {
                    currentBuild.displayName = "${params.PPG_REPO}-${params.IMAGE_TAG}-${params.IMAGE_TYPE}"
                }
            }
        }
        stage ('Build image') {
            when {
                not {
                    allOf {
                        environment name: 'TARGET_REPO', value: 'DockerHub'
                        expression { return params.IMAGE_TYPE == 'regular' }
                    }
                }
            }
            steps {
                sh """
                    sudo rm -f /usr/libexec/docker/cli-plugins/docker-buildx
                    export DOCKER_CLI_EXPERIMENTAL=enabled
                    sudo mkdir -p /usr/libexec/docker/cli-plugins/
                    sudo curl -L https://github.com/docker/buildx/releases/download/v0.30.0/buildx-v0.30.0.linux-arm64 -o /usr/libexec/docker/cli-plugins/docker-buildx
                    sudo chmod +x /usr/libexec/docker/cli-plugins/docker-buildx
                    sudo systemctl restart docker
                    export DOCKER_BUILDKIT=1
                """
                script {
                    if (params.BUILD_UBI9) {
                        sh """
                            PG_MAJOR=\$(echo ${params.IMAGE_TAG} | cut -f1 -d'-' | cut -f1 -d'.')
                            echo "PG_MAJOR=\$PG_MAJOR"
                            git clone https://github.com/percona/percona-docker
                            cd percona-docker/percona-distribution-postgresql-upgrade
                            sed -i "s|ppg-\\\${PG_MAJOR} release|ppg-\\\${PG_MAJOR} ${params.PPG_REPO}|g" Dockerfile
                            sed -i "s|ppg-\\\${pg_version} release|ppg-\\\${pg_version} ${params.PPG_REPO}|g" Dockerfile
                            sed -i "s|x86_64|aarch64|g" Dockerfile
                            sed -i "s|ppg-\\\${PG_MAJOR} release|ppg-\\\${PG_MAJOR} ${params.PPG_REPO}|g" Dockerfile-custom
                            sed -i "s|ppg-\\\${pg_version} release|ppg-\\\${pg_version} ${params.PPG_REPO}|g" Dockerfile-custom

                            if [ "${params.IMAGE_TYPE}" = "both" ] || [ "${params.IMAGE_TYPE}" = "regular" ]; then
                                docker build --platform=linux/arm64 --no-cache --provenance=false \\
                                    --build-arg PG_MAJOR=\$PG_MAJOR \\
                                    -t percona-distribution-postgresql-upgrade:${params.IMAGE_TAG} \\
                                    -f Dockerfile .
                            fi
                            if [ "${params.IMAGE_TYPE}" = "both" ] || [ "${params.IMAGE_TYPE}" = "custom" ]; then
                                docker build --platform=linux/arm64 --no-cache --provenance=false \\
                                    --build-arg PG_MAJOR=\$PG_MAJOR \\
                                    -t percona-distribution-postgresql-upgrade-custom:${params.IMAGE_TAG} \\
                                    -f Dockerfile-custom .
                            fi
                        """
                    }
                    if (params.BUILD_UBI8) {
                        sh """
                            PG_MAJOR=\$(echo ${params.IMAGE_TAG} | cut -f1 -d'-' | cut -f1 -d'.')
                            echo "PG_MAJOR=\$PG_MAJOR"
                            git clone https://github.com/percona/percona-docker percona-docker-ubi8
                            cd percona-docker-ubi8/percona-distribution-postgresql-upgrade
                            sed -i "s|ppg-\\\${PG_MAJOR} release|ppg-\\\${PG_MAJOR} ${params.PPG_REPO}|g" Dockerfile-ubi8
                            sed -i "s|ppg-\\\${pg_version} release|ppg-\\\${pg_version} ${params.PPG_REPO}|g" Dockerfile-ubi8

                            if [ "${params.IMAGE_TYPE}" = "both" ] || [ "${params.IMAGE_TYPE}" = "regular" ]; then
                                docker build --platform=linux/arm64 --no-cache --provenance=false \\
                                    --build-arg PG_MAJOR=\$PG_MAJOR \\
                                    -t percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-ubi8 \\
                                    -f Dockerfile-ubi8 .
                            fi
                        """
                    }
                }
            }
        }
        stage ('Push regular image to perconalab') {
            when {
                allOf {
                    environment name: 'TARGET_REPO', value: 'PerconaLab'
                    expression { return params.IMAGE_TYPE == 'both' || params.IMAGE_TYPE == 'regular' }
                }
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    script {
                        sh "docker login -u '${USER}' -p '${PASS}'"
                        if (params.BUILD_UBI9) {
                            sh """
                                MAJ_TAG=\$(echo ${params.IMAGE_TAG} | sed -E 's/-[0-9]+\$//' | sed -E 's/\\.[0-9]+//g')
                                echo "MAJ_TAG=\$MAJ_TAG"
                                docker tag percona-distribution-postgresql-upgrade:${params.IMAGE_TAG} perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-arm64
                                docker push perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-arm64

                                docker manifest create --amend perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG} \\
                                   perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-amd64 \\
                                   perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG} \\
                                   perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG} \\
                                   perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}
                                docker manifest push perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}

                                docker manifest create --amend perconalab/percona-distribution-postgresql-upgrade:\$MAJ_TAG \\
                                   perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-amd64 \\
                                   perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql-upgrade:\$MAJ_TAG \\
                                   perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql-upgrade:\$MAJ_TAG \\
                                   perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql-upgrade:\$MAJ_TAG
                                docker manifest push perconalab/percona-distribution-postgresql-upgrade:\$MAJ_TAG
                            """
                        }
                        if (params.BUILD_UBI8) {
                            sh """
                                MAJ_TAG=\$(echo ${params.IMAGE_TAG} | sed -E 's/-[0-9]+\$//' | sed -E 's/\\.[0-9]+//g')
                                echo "MAJ_TAG=\$MAJ_TAG"
                                docker tag percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-ubi8 perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-ubi8-arm64
                                docker push perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-ubi8-arm64

                                docker manifest create --amend perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-ubi8 \\
                                   perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-ubi8-amd64 \\
                                   perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-ubi8-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-ubi8 \\
                                   perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-ubi8-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-ubi8 \\
                                   perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-ubi8-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-ubi8
                                docker manifest push perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-ubi8

                                docker manifest create --amend perconalab/percona-distribution-postgresql-upgrade:\$MAJ_TAG-ubi8 \\
                                   perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-ubi8-amd64 \\
                                   perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-ubi8-arm64
                                docker manifest annotate perconalab/percona-distribution-postgresql-upgrade:\$MAJ_TAG-ubi8 \\
                                   perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-ubi8-arm64 --os linux --arch arm64 --variant v8
                                docker manifest annotate perconalab/percona-distribution-postgresql-upgrade:\$MAJ_TAG-ubi8 \\
                                   perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-ubi8-amd64 --os linux --arch amd64
                                docker manifest inspect perconalab/percona-distribution-postgresql-upgrade:\$MAJ_TAG-ubi8
                                docker manifest push perconalab/percona-distribution-postgresql-upgrade:\$MAJ_TAG-ubi8
                            """
                        }
                    }
                }
            }
        }
        stage ('Push regular image to percona') {
            when {
                allOf {
                    environment name: 'TARGET_REPO', value: 'DockerHub'
                    expression { return params.IMAGE_TYPE == 'both' || params.IMAGE_TYPE == 'regular' }
                }
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    script {
                        sh """
                            sudo rm -f /usr/libexec/docker/cli-plugins/docker-buildx
                            export DOCKER_CLI_EXPERIMENTAL=enabled
                            sudo mkdir -p /usr/libexec/docker/cli-plugins/
                            sudo curl -L https://github.com/docker/buildx/releases/download/v0.30.0/buildx-v0.30.0.linux-arm64 -o /usr/libexec/docker/cli-plugins/docker-buildx
                            sudo chmod +x /usr/libexec/docker/cli-plugins/docker-buildx
                            sudo systemctl restart docker
                            docker login -u '${USER}' -p '${PASS}'
                        """
                        if (params.BUILD_UBI9) {
                            sh """
                                MAJ_TAG=\$(echo ${params.IMAGE_TAG} | sed -E 's/-[0-9]+\$//' | sed -E 's/\\.[0-9]+//g')
                                echo "MAJ_TAG=\$MAJ_TAG"
                                docker buildx imagetools create \\
                                    -t percona/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-arm64 \\
                                    perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-arm64
                                docker buildx imagetools create \\
                                    -t percona/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG} \\
                                    perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-amd64 \\
                                    perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-arm64
                                docker buildx imagetools create \\
                                    -t percona/percona-distribution-postgresql-upgrade:\$MAJ_TAG \\
                                    perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-amd64 \\
                                    perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-arm64
                            """
                        }
                        if (params.BUILD_UBI8) {
                            sh """
                                MAJ_TAG=\$(echo ${params.IMAGE_TAG} | sed -E 's/-[0-9]+\$//' | sed -E 's/\\.[0-9]+//g')
                                echo "MAJ_TAG=\$MAJ_TAG"
                                docker buildx imagetools create \\
                                    -t percona/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-ubi8-arm64 \\
                                    perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-ubi8-arm64
                                docker buildx imagetools create \\
                                    -t percona/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-ubi8 \\
                                    perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-ubi8-amd64 \\
                                    perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-ubi8-arm64
                                docker buildx imagetools create \\
                                    -t percona/percona-distribution-postgresql-upgrade:\$MAJ_TAG-ubi8 \\
                                    perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-ubi8-amd64 \\
                                    perconalab/percona-distribution-postgresql-upgrade:${params.IMAGE_TAG}-ubi8-arm64
                            """
                        }
                    }
                }
            }
        }
        stage ('Push custom image to perconalab') {
            when {
                expression { return params.IMAGE_TYPE == 'both' || params.IMAGE_TYPE == 'custom' }
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                     sh """
                         docker login -u '${USER}' -p '${PASS}'
                         MAJ_TAG=\$(echo ${params.IMAGE_TAG} | sed -E 's/-[0-9]+\$//' | sed -E 's/\\.[0-9]+//g')
                         echo "MAJ_TAG=\$MAJ_TAG"
                         docker tag percona-distribution-postgresql-upgrade-custom:${params.IMAGE_TAG} perconalab/percona-distribution-postgresql-upgrade-custom:${params.IMAGE_TAG}-arm64
                         docker push perconalab/percona-distribution-postgresql-upgrade-custom:${params.IMAGE_TAG}-arm64

                         docker manifest create --amend perconalab/percona-distribution-postgresql-upgrade-custom:${params.IMAGE_TAG} \\
                            perconalab/percona-distribution-postgresql-upgrade-custom:${params.IMAGE_TAG}-amd64 \\
                            perconalab/percona-distribution-postgresql-upgrade-custom:${params.IMAGE_TAG}-arm64
                         docker manifest annotate perconalab/percona-distribution-postgresql-upgrade-custom:${params.IMAGE_TAG} \\
                            perconalab/percona-distribution-postgresql-upgrade-custom:${params.IMAGE_TAG}-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate perconalab/percona-distribution-postgresql-upgrade-custom:${params.IMAGE_TAG} \\
                            perconalab/percona-distribution-postgresql-upgrade-custom:${params.IMAGE_TAG}-amd64 --os linux --arch amd64
                         docker manifest inspect perconalab/percona-distribution-postgresql-upgrade-custom:${params.IMAGE_TAG}
                         docker manifest push perconalab/percona-distribution-postgresql-upgrade-custom:${params.IMAGE_TAG}

                         docker manifest create --amend perconalab/percona-distribution-postgresql-upgrade-custom:\$MAJ_TAG \\
                            perconalab/percona-distribution-postgresql-upgrade-custom:${params.IMAGE_TAG}-amd64 \\
                            perconalab/percona-distribution-postgresql-upgrade-custom:${params.IMAGE_TAG}-arm64
                         docker manifest annotate perconalab/percona-distribution-postgresql-upgrade-custom:\$MAJ_TAG \\
                            perconalab/percona-distribution-postgresql-upgrade-custom:${params.IMAGE_TAG}-arm64 --os linux --arch arm64 --variant v8
                         docker manifest annotate perconalab/percona-distribution-postgresql-upgrade-custom:\$MAJ_TAG \\
                            perconalab/percona-distribution-postgresql-upgrade-custom:${params.IMAGE_TAG}-amd64 --os linux --arch amd64
                         docker manifest inspect perconalab/percona-distribution-postgresql-upgrade-custom:\$MAJ_TAG
                         docker manifest push perconalab/percona-distribution-postgresql-upgrade-custom:\$MAJ_TAG
                     """
                }
            }
        }
    }
    post {
        always {
            sh """
                sudo docker rmi -f \$(sudo docker images -q | uniq) || true
                sudo rm -rf ${WORKSPACE}/*
            """
            deleteDir()
        }
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: Building of PPG upgrade ${IMAGE_TAG} (${IMAGE_TYPE}) for ARM, repo ${PPG_REPO} succeed")
        }
        unstable {
            slackNotify("#releases-ci", "#F6F930", "[${JOB_NAME}]: Building of PPG upgrade ${IMAGE_TAG} (${IMAGE_TYPE}) for ARM, repo ${PPG_REPO} unstable - [${BUILD_URL}testReport/]")
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: Building of PPG upgrade ${IMAGE_TAG} (${IMAGE_TYPE}) for ARM, repo ${PPG_REPO} failed - [${BUILD_URL}]")
        }
    }
}

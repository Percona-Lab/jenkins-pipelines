library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
    }
    parameters {
        choice(name: 'CLOUD', choices: [ 'Hetzner','AWS' ], description: 'Cloud infra for build')
        string(name: 'PBM_BRANCH_OR_COMMIT', defaultValue: 'dev', description: 'PBM branch or commit to build from')
    }
    triggers {
        cron('0 0 * * *')
    }
    options {
        disableConcurrentBuilds()
    }
    stages {
        stage('Set build name'){
            steps {
                script {
                    currentBuild.displayName = "${params.PBM_BRANCH_OR_COMMIT}-nightly"
                }
            }
        }
        stage ('Build image') {
            steps {
                sh """
                    git clone https://github.com/percona/percona-docker
                    cd percona-docker/percona-backup-mongodb
                    docker build . -f Dockerfile.nightly --build-arg PBM_BRANCH_OR_COMMIT="${params.PBM_BRANCH_OR_COMMIT}" -t percona-backup-mongodb
                    """
            }
        }
        stage ('Run trivy analyzer') {
            steps {
                sh """
                    TRIVY_VERSION=\$(curl --silent 'https://api.github.com/repos/aquasecurity/trivy/releases/latest' | grep '"tag_name":' | tr -d '"' | sed -E 's/.*v(.+),.*/\\1/')
                    wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz
                    sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz -C /usr/local/bin/
                    wget https://raw.githubusercontent.com/aquasecurity/trivy/v\${TRIVY_VERSION}/contrib/junit.tpl
                    curl https://raw.githubusercontent.com/Percona-QA/psmdb-testing/main/docker/trivyignore -o ".trivyignore"
                    /usr/local/bin/trivy -q image --format template --template @junit.tpl  -o trivy-high-junit.xml \
                                         --timeout 10m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL percona-backup-mongodb
                    """
            }
            post {
                always {
                    junit testResults: "*-junit.xml", keepLongStdio: true, allowEmptyResults: true, skipPublishingChecks: true
                }
            }
        }
        stage ('Push images to perconalab') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                     sh """
                         docker login -u '${USER}' -p '${PASS}'
                         docker tag percona-backup-mongodb perconalab/percona-backup-mongodb:nightly
                         docker push perconalab/percona-backup-mongodb:nightly
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
            slackNotify("#mongodb_autofeed", "#00FF00", "[${JOB_NAME}]: Building of PBM nightly branch/commit ${params.PBM_BRANCH_OR_COMMIT} succeeded")
        }
        unstable {
            slackNotify("#mongodb_autofeed", "#F6F930", "[${JOB_NAME}]: Building of PBM nightly branch/commit ${params.PBM_BRANCH_OR_COMMIT} unstable - [${BUILD_URL}testReport/]")
        }
        failure {
            slackNotify("#mongodb_autofeed", "#FF0000", "[${JOB_NAME}]: Building of PBM nightly branch/commit ${params.PBM_BRANCH_OR_COMMIT} failed - [${BUILD_URL}]")
        }
    }
}

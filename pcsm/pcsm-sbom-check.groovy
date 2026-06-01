library changelog: false, identifier: "lib@hetzner", retriever: modernSCM([
        $class: 'GitSCMSource',
        remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
    agent {
        label 'docker-x64'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
    }
    parameters {
        string(
                defaultValue: '0.9.0',
                description: 'PCSM Version for tests',
                name: 'PCSM_VERSION')
        string(
                defaultValue: 'main',
                description: 'Branch for testing repository',
                name: 'TESTING_BRANCH')
        choice(
                choices: ['perconalab', 'percona'],
                description: 'Docker image repository (perconalab for testing, percona for release)',
                name: 'IMAGE_REPO')
    }
    stages {
        stage('Set build name'){
            steps {
                script {
                    currentBuild.displayName = "${params.PCSM_VERSION}"
                }
            }
        }
        stage('Checkout') {
            steps {
                deleteDir()
                git poll: false, branch: TESTING_BRANCH, url: 'https://github.com/Percona-QA/psmdb-testing.git'
            }
        }
        stage('Tarball SBOM') {
            steps {
                catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                    sh """
                        docker run --env PCSM_VERSION=${PCSM_VERSION} --rm -v `pwd`:/workspace -w /workspace python bash -c 'wget -qO- https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sh -s -- -b /usr/local/bin && curl -fsSL -o /usr/local/bin/cyclonedx https://github.com/CycloneDX/cyclonedx-cli/releases/latest/download/cyclonedx-linux-x64 && chmod +x /usr/local/bin/cyclonedx && pip3 install requests pytest && pytest -s --junitxml=tarball_checks/junit.xml tarball_checks/test_pcsm_tarball.py'
                    """
                }
            }
        }
        stage('Docker Image SBOM') {
            steps {
                script {
                    sh """
                        docker run --env PCSM_VERSION=${PCSM_VERSION} --env IMAGE_REPO=${IMAGE_REPO} --rm -v /var/run/docker.sock:/var/run/docker.sock -v `pwd`:/workspace -w /workspace python bash -c 'wget -qO- https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sh -s -- -b /usr/local/bin && curl -fsSL https://download.docker.com/linux/static/stable/x86_64/docker-27.5.1.tgz | tar xz -C /usr/local/bin --strip-components=1 docker/docker && curl -fsSL https://github.com/oras-project/oras/releases/download/v1.2.3/oras_1.2.3_linux_amd64.tar.gz | tar xz -C /usr/local/bin oras && curl -fsSL -o /usr/local/bin/cyclonedx https://github.com/CycloneDX/cyclonedx-cli/releases/latest/download/cyclonedx-linux-x64 && chmod +x /usr/local/bin/cyclonedx && pip3 install pytest && pytest -s --junitxml=docker-sbom-check/junit.xml docker-sbom-check/test_pcsm_docker_sbom.py'
                    """
                }
            }
        }
    }
    post {
//        success {
//            slackNotify("#mongodb_autofeed", "#00FF00", "[${JOB_NAME}]: SBOM checks for PCSM ${PCSM_VERSION} - ok [${BUILD_URL}testReport/]")
//        }
//        unstable {
//            slackNotify("#mongodb_autofeed", "#F6F930", "[${JOB_NAME}]: SBOM checks for PCSM ${PCSM_VERSION} - some tests failed [${BUILD_URL}testReport/]")
//        }
//        failure {
//            slackNotify("#mongodb_autofeed", "#FF0000", "[${JOB_NAME}]: SBOM checks for PCSM ${PCSM_VERSION} - failed [${BUILD_URL}]")
//        }
        always {
            script {
                junit testResults: "**/junit.xml", keepLongStdio: true, allowEmptyResults: true, skipPublishingChecks: true
                sh '''
                    sudo rm -rf ./*
                '''
                deleteDir()
            }
        }
    }
}

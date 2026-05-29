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
                defaultValue: '0.6.0',
                description: 'PCSM Version for tests',
                name: 'PCSM_VERSION')
        string(
                defaultValue: 'main',
                description: 'Branch for testing repository',
                name: 'TESTING_BRANCH')
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
        stage('Test') {
            steps {
                script {
                    sh """
                        cd tarball_checks
                        docker run --env PCSM_VERSION=${PCSM_VERSION} --rm -v `pwd`:/tmp -w /tmp python bash -c 'wget -qO- https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sh -s -- -b /usr/local/bin && curl -fsSL -o /usr/local/bin/cyclonedx https://github.com/CycloneDX/cyclonedx-cli/releases/latest/download/cyclonedx-linux-x64 && chmod +x /usr/local/bin/cyclonedx && pip3 install requests pytest && pytest -s --junitxml=junit.xml test_pcsm_tarball.py' || [ \$? = 1 ]
                    """
                }
            }
        }
    }
    post {
        success {
            slackNotify("#mongodb_autofeed", "#00FF00", "[${JOB_NAME}]: checking tar ball packages for PCSM ${PCSM_VERSION} - ok [${BUILD_URL}testReport/]")
        }
        unstable {
            slackNotify("#mongodb_autofeed", "#F6F930", "[${JOB_NAME}]: checking tar ball packages for PCSM ${PCSM_VERSION} - some links are broken [${BUILD_URL}testReport/]")
        }
        failure {
            slackNotify("#mongodb_autofeed", "#FF0000", "[${JOB_NAME}]: checking tar ball packages for PCSM ${PCSM_VERSION} - failed [${BUILD_URL}]" )
        }
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

library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
    }
    parameters {
        choice(
            name: 'CLOUD',
            choices: [ 'Hetzner','AWS' ],
            description: 'Cloud infra for build')
        string(
            defaultValue: '7.0.2-1',
            description: 'PSMDB Version for tests',
            name: 'PSMDB_VERSION')
        string(
            defaultValue: 'main',
            description: 'Branch for testing repository',
            name: 'TESTING_BRANCH')
    }
    stages {
        stage('Set build name'){
            steps {
                script {
                    currentBuild.displayName = "${params.PSMDB_VERSION}"
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
                        cd site_checks
                        docker run --env PSMDB_VERSION=${params.PSMDB_VERSION} --rm -v `pwd`:/tmp -w /tmp python bash -c 'pip3 install requests pytest setuptools && pytest -s --junitxml=junit.xml test_psmdb.py || [ \$? = 1 ] '
                    """
                }
            }
        }
    }
    post {
        success {
            slackNotify("#mongodb_autofeed", "#00FF00", "[${JOB_NAME}]: checking packages on the main site for PSMDB ${PSMDB_VERSION} - ok [${BUILD_URL}testReport/]")
        }
        unstable {
            slackNotify("#mongodb_autofeed", "#F6F930", "[${JOB_NAME}]: checking packages on the main site for PSMDB ${PSMDB_VERSION} - some links are broken [${BUILD_URL}testReport/]")
        }
        failure {
            slackNotify("#mongodb_autofeed", "#FF0000", "[${JOB_NAME}]: checking packages on the main site for PSMDB ${PSMDB_VERSION} - failed [${BUILD_URL}]" )
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

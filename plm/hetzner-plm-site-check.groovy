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
            description: 'PLM Version for tests',
            name: 'PLM_VERSION')
        string(
            defaultValue: 'main',
            description: 'Branch for testing repository',
            name: 'TESTING_BRANCH')
    }
    stages {
        stage('Set build name'){
            steps {
                script {
                    currentBuild.displayName = "${params.PLM_VERSION}"
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
                        docker run --env PLM_VERSION=${params.PLM_VERSION} --rm -v `pwd`:/tmp -w /tmp python bash -c 'pip3 install requests pytest setuptools && pytest -s --junitxml=junit.xml test_plm.py || [ \$? = 1 ] '
                    """
                }
            }
        }
    }
    post {
        success {
            slackNotify("#mongodb_autofeed", "#00FF00", "[${JOB_NAME}]: checking packages on the main site for PLM ${PLM_VERSION} - ok [${BUILD_URL}testReport/]")
        }
        unstable {
            slackNotify("#mongodb_autofeed", "#F6F930", "[${JOB_NAME}]: checking packages on the main site for PLM ${PLM_VERSION} - some links are broken [${BUILD_URL}testReport/]")
        }
        failure {
            slackNotify("#mongodb_autofeed", "#FF0000", "[${JOB_NAME}]: checking packages on the main site for PLM ${PLM_VERSION} - failed [${BUILD_URL}]" )
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

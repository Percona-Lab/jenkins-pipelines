library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
    agent {
        label 'docker'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
    }
    parameters {
        string(
            defaultValue: '8.0.34-26.1',
            description: 'Full PS Version for tests. Examples: 8.0.34-26.1; 8.1.0-1.1',
            name: 'PS_VER_FULL')
        string(
            defaultValue: '8.0.34-29.1',
            description: 'Full PXB Version for tests. Examples: 8.0.34-29.1; 8.1.0-1.1',
            name: 'PXB_VER_FULL')
        string(
            defaultValue: '3.2.6-10',
            description: 'Full Orchestrator Version for tests. Example: 3.2.6-10',
            name: 'ORCH_VER_FULL')
        string(
            defaultValue: '3.5.4',
            description: 'PT Version for tests. Example: 3.5.4',
            name: 'PT_VER')
        string(
            defaultValue: '2.5.5',
            description: 'Proxysql Version for tests. Example: 2.5.5',
            name: 'PROXYSQL_VER')
        string(
            defaultValue: 'master',
            description: 'Branch for testing repository',
            name: 'TESTING_BRANCH')
        string(
            defaultValue: 'Percona-QA',
            description: 'Branch for testing repository',
            name: 'TESTING_GIT_ACCOUNT')
    }
    stages {
        stage('Set build name'){
            steps {
                script {
                    currentBuild.displayName = "#${BUILD_NUMBER}-${params.PS_VER_FULL}-${params.TESTING_BRANCH}"
                }
            }
        }
        stage('Checkout') {
            steps {
                deleteDir()
                git poll: false, branch: TESTING_BRANCH, url: "https://github.com/${TESTING_GIT_ACCOUNT}/package-testing.git"
            }
        }
        stage('Test') {
            steps {
                script {
                    sh """
                        cd site_checks
                        docker run --env PS_VER_FULL=${params.PS_VER_FULL} --env PXB_VER_FULL=${params.PXB_VER_FULL} --env ORCH_VER_FULL=${params.ORCH_VER_FULL} \
                            --env PT_VER=${params.PT_VER} --env PROXYSQL_VER=${params.PROXYSQL_VER} \
                            --rm -v `pwd`:/tmp -w /tmp python bash -c \
                            'pip3 install requests pytest setuptools && pytest -s --junitxml=junit.xml test_pdps.py || [ \$? = 1 ] '
                    """
                }
            }
        }
    }
    post {
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

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
            description: 'Full PXC version for tests. Examples: 8.0.34-26.1; 8.1.0-1.1',
            name: 'PXC_VER_FULL')
        string(
            defaultValue: '8.0.34-29.1',
            description: 'Full PXB version for tests. Examples: 8.0.34-29.1; 8.1.0-1.1',
            name: 'PXB_VER_FULL')
        string(
            defaultValue: '3.5.5',
            description: 'PT version for tests. Example: 3.5.5',
            name: 'PT_VER')
        string(
            defaultValue: '2.5.5',
            description: 'Proxysql version for tests. Example: 2.5.5',
            name: 'PROXYSQL_VER')
        string(
            defaultValue: '2.8.1',
            description: 'HAproxy version for tests. Example: 2.8.1',
            name: 'HAPROXY_VER')
        string(
            defaultValue: '1.0',
            description: 'Replication manager version for tests.  Example: 1.0',
            name: 'REPL_MAN_VER')
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
                    currentBuild.displayName = "#${BUILD_NUMBER}-${params.PXC_VER_FULL}-${params.TESTING_BRANCH}"
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
                        docker run --env PXC_VER_FULL=${params.PXC_VER_FULL} --env PXB_VER_FULL=${params.PXB_VER_FULL} --env PT_VER=${params.PT_VER} \
                            --env PROXYSQL_VER=${params.PROXYSQL_VER} --env HAPROXY_VER=${params.HAPROXY_VER} --env REPL_MAN_VER=${params.REPL_MAN_VER} \
                            --rm -v `pwd`:/tmp -w /tmp python bash -c \
                            'pip3 install requests pytest setuptools && pytest -s --junitxml=junit.xml test_pdpxc.py || [ \$? = 1 ] '
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

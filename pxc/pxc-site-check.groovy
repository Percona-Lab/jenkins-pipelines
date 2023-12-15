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
            description: 'Full PXC version for tests. Examples: 5.7.43-31.65.1; 8.0.34-26.1; 8.1.0-1.1',
            name: 'PXC_VER_FULL')
        string(
            defaultValue: '47',
            description: 'PXC 5.7 INNODB version for tests. Only for 5.7. Leave default for PXC 8.0 +',
            name: 'PXC57_INNODB')
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
                        docker run --env PXC_VER_FULL=${params.PXC_VER_FULL} --env PXC57_INNODB=${params.PXC57_INNODB} \
                            --rm -v `pwd`:/tmp -w /tmp python bash -c \
                            'pip3 install requests pytest setuptools && pytest -s --junitxml=junit.xml test_pxc.py || [ \$? = 1 ] '
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

pipeline_timeout = 10

pipeline {
    parameters {
        string(
            defaultValue: 'https://github.com/sysown/proxysql',
            description: 'URL to ProxySQL repository',
            name: 'GIT_REPO',
            trim: true)
        string(
            defaultValue: 'v2.3.2',
            description: 'Tag/Branch for ProxySQL repository',
            name: 'BRANCH',
            trim: true)
        string(
            defaultValue: 'https://github.com/percona/proxysql-packaging',
            description: 'URL to ProxySQL package repository',
            name: 'PROXYSQL_PACKAGE_REPO',
            trim: true)
        string(
            defaultValue: 'v2.1',
            description: 'Tag/Branch for ProxySQL package repository',
            name: 'PROXYSQL_PACKAGE_BRANCH',
            trim: true)
        string(
            defaultValue: 'v2.3.2-1-dev',
            description: 'Tag/Branch for ProxySQL-admin-tool repository',
            name: 'PAT_TAG',
            trim: true)
       choice(
            choices: 'PXC80\nPXC57',
            description: 'PXC version to test proxysql-admin suite',
            name: 'PXC_VERSION')
       choice(
            choices: 'centos:7\ncentos:8\nubuntu:bionic\nubuntu:focal\ndebian:stretch\ndebian:buster',
            description: 'OS version for compilation',
            name: 'DOCKER_OS')
        choice(
            choices: '/usr/bin/cmake',
            description: 'path to cmake binary',
            name: 'JOB_CMAKE')
        choice(
            choices: 'RelWithDebInfo\nDebug',
            description: 'Type of build to produce',
            name: 'CMAKE_BUILD_TYPE')
        string(
            defaultValue: '',
            description: 'cmake options',
            name: 'CMAKE_OPTS')
        string(
            defaultValue: '',
            description: 'make options, like VERBOSE=1',
            name: 'MAKE_OPTS')
    }
    agent {
        label 'micro-amazon'
    }
    options {
        skipDefaultCheckout()
        skipStagesAfterUnstable()
        timeout(time: 6, unit: 'DAYS')
        buildDiscarder(logRotator(numToKeepStr: '200', artifactNumToKeepStr: '200'))
    }
    stages {
        stage('Build ProxySQL') {
                agent { label 'docker' }
                steps {
                    git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                    echo 'Checkout ProxySQL sources'
                    sh '''
                        # sudo is needed for better node recovery after compilation failure
                        # if building failed on compilation stage directory will have files owned by docker user
                        sudo git reset --hard
                        sudo git clean -xdf
                        sudo rm -rf sources
                        ./proxysql/checkout PROXYSQL
                    '''

                    echo 'Build ProxySQL'
                    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'c42456e5-c28d-4962-b32c-b75d161bff27', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                        sh '''
                            aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0
                            sg docker -c "
                                if [ \$(docker ps -q | wc -l) -ne 0 ]; then
                                    docker ps -q | xargs docker stop --time 1 || :
                                fi
                                ./proxysql/run-build-proxysql ${DOCKER_OS}
                            " 2>&1 | tee build.log
                          
                            if [[ -f \$(ls ./proxysql/sources/proxysql/results/*.tar.gz | head -1) ]]; then
                                until aws s3 cp --no-progress --acl public-read proxysql/sources/proxysql/results/*.tar.gz s3://pxc-build-cache/${BUILD_TAG}/proxysql-${BRANCH}.tar.gz; do
                                    sleep 5
                                done
                            else
                                echo cannot find compiled archive
                                exit 1
                            fi
                        '''
                   }
                }
        }
        stage('Test ProxySQL') {
                agent { label 'docker' }
                steps {
                    git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                    echo 'Test ProxySQL'
                    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'c42456e5-c28d-4962-b32c-b75d161bff27', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                        sh '''
                            until aws s3 cp --no-progress s3://pxc-build-cache/${BUILD_TAG}/proxysql-${BRANCH}.tar.gz ./proxysql/sources/proxysql/results/proxysql-${BRANCH}.tar.gz; do
                                sleep 5
                            done
                            aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0
                            sg docker -c "
                                if [ \$(docker ps -q | wc -l) -ne 0 ]; then
                                    docker ps -q | xargs docker stop --time 1 || :
                                fi
                                ./proxysql/run-test-proxysql ${DOCKER_OS}
                            "
                        '''
                    }
                    step([$class: 'JUnitResultArchiver', testResults: 'proxysql/sources/proxysql/results/*.xml', healthScaleFactor: 1.0])
                    archiveArtifacts 'proxysql/sources/proxysql/results/*.output,proxysql/sources/proxysql/results/*.xml,proxysql/sources/proxysql/results/qa_proxysql_logs.tar.gz'
                }
        }
    }
    post {
        always {
            sh '''
                echo Finish: \$(date -u "+%s")
            '''
        }
    }
}

pipeline_timeout = 10

pipeline {
    parameters {
        string(
            defaultValue: 'https://github.com/percona/percona-xtrabackup',
            description: 'URL to PXB80 repository',
            name: 'PXB80_REPO',
            trim: true)
        string(
            defaultValue: '8.0',
            description: 'Tag/Branch for PXC repository',
            name: 'PXB80_BRANCH',
            trim: true)
        choice(
            choices: 'centos:7\ncentos:8\nubuntu:xenial\nubuntu:bionic\nubuntu:focal\ndebian:stretch\ndebian:buster',
            description: 'OS version for compilation',
            name: 'DOCKER_OS')
        choice(
            choices: 'innodb80\nxtradb80\ngalera80',
            description: 'MySQL version for QA run',
            name: 'XTRABACKUP_TARGET')
        string(
            defaultValue: '8.0.20-11',
            description: 'Version of MS/PS/PXC Server which will be used for bootstrap.sh script',
            name: 'XTRABACKUP_TARGET_VERSION',
            trim: true)
        string(
            defaultValue: '',
            description: 'Pass an URL for downloading bootstrap.sh, If empty will use from repository you specified in PXB80_REPO',
            name: 'BOOTSTRAP_URL',
            trim: true)
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
        string(
            defaultValue: '-j 2',
            description: './run.sh options, for options like: -j N Run tests in N parallel processes, -T seconds, -x options  Extra options to pass to xtrabackup',
            name: 'XBTR_ARGS')
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
        stage('Check out and Build PXB') {
            parallel {
                stage('Build PXB80') {
                    agent { label 'docker' }
                    steps {
                        git branch: 'PXB-2261-Add-PXB-QA-job', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                        echo 'Checkout PXB80 sources'
                        sh '''
                            # sudo is needed for better node recovery after compilation failure
                            # if building failed on compilation stage directory will have files owned by docker user
                            sudo git reset --hard
                            sudo git clean -xdf
                            sudo rm -rf sources
                            ./pxb/docker/checkout PXB80
                        '''
                        echo 'Build PXB80'
                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'c42456e5-c28d-4962-b32c-b75d161bff27', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            sh '''
                                sg docker -c "
                                    if [ \$(docker ps -q | wc -l) -ne 0 ]; then
                                        docker ps -q | xargs docker stop --time 1 || :
                                    fi
                                    ./pxb/docker/run-build-pxb80 ${DOCKER_OS}
                                " 2>&1 | tee build.log

                                if [[ -f \$(ls pxb/sources/pxb80/results/*.tar.gz | head -1) ]]; then
                                    until aws s3 cp --no-progress --acl public-read pxb/sources/pxb80/results/*.tar.gz s3://pxb-build-cache/${BUILD_TAG}/pxb80.tar.gz; do
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
            }
        }
        stage('Test PXB80') {
                agent { label 'docker' }
                steps {
                    git branch: 'PXB-2261-Add-PXB-QA-job', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                    echo 'Test PXB80'
                    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'c42456e5-c28d-4962-b32c-b75d161bff27', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                        sh '''
                            until aws s3 cp --no-progress s3://pxb-build-cache/${BUILD_TAG}/pxb80.tar.gz ./pxb/sources/pxb80/results/pxb80.tar.gz; do
                                sleep 5
                            done
                            sg docker -c "
                                if [ \$(docker ps -q | wc -l) -ne 0 ]; then
                                    docker ps -q | xargs docker stop --time 1 || :
                                fi
                                ./pxb/docker/run-test-pxb80 ${DOCKER_OS}
                            "
                        '''
                    }
                    step([$class: 'JUnitResultArchiver', testResults: 'pxb/sources/pxb80/results/*.xml', healthScaleFactor: 1.0])
                    archiveArtifacts 'pxb/sources/pxb24/results/*.xml,pxb/sources/pxb80/results/*.output,pxb/sources/pxb80/results/pxb80-test-xbtr_logs.tar.gz'
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

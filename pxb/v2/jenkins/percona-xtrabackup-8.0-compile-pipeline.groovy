pipeline {
    parameters {
        string(
            defaultValue: 'https://github.com/percona/percona-xtrabackup',
            description: 'URL to percona-xtrabackup repository',
            name: 'GIT_REPO',
            trim: true)
        string(
            defaultValue: '8.0',
            description: 'Tag/Branch for percona-xtrabackup repository',
            name: 'BRANCH',
            trim: true)
        choice(
            choices: 'centos:7\ncentos:8\noraclelinux:9\nubuntu:bionic\nubuntu:focal\nubuntu:jammy\ndebian:buster\ndebian:bullseye\nasan',
            description: 'OS version for compilation',
            name: 'DOCKER_OS')
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
        choice(
            choices: 'docker-32gb\ndocker',
            description: 'Run build on specified instance type',
            name: 'LABEL')
    }
    agent {
        label 'micro-amazon'
    }
    options {
        skipDefaultCheckout()
        skipStagesAfterUnstable()
        timeout(time: 180, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '200', artifactNumToKeepStr: '200'))
    }
    stages {
        stage('Build') {
            agent { label LABEL }
            steps {
                timeout(time: 60, unit: 'MINUTES')  {
                    script {
                        currentBuild.displayName = "${BUILD_NUMBER} ${CMAKE_BUILD_TYPE}/${DOCKER_OS}"
                    }
                    sh 'echo Prepare: \$(date -u "+%s")'
                    echo 'Checking Percona XtraBackup branch version, JEN-913 prevent wrong version run'
                    sh '''
                        MY_BRANCH_BASE_MAJOR=8
                        MY_BRANCH_BASE_MINOR=0
                        RAW_VERSION_LINK=$(echo ${GIT_REPO%.git} | sed -e "s:github.com:raw.githubusercontent.com:g")
                        curl ${RAW_VERSION_LINK}/${BRANCH}/XB_VERSION --output ${WORKSPACE}/XB_VERSION-${BUILD_NUMBER}
                        source ${WORKSPACE}/XB_VERSION-${BUILD_NUMBER}
                        if [[ ${XB_VERSION_MAJOR} -lt ${MY_BRANCH_BASE_MAJOR} ]] ; then
                            echo "Are you trying to build wrong branch?"
                            echo "You are trying to build ${XB_VERSION_MAJOR}.${XB_VERSION_MINOR} instead of ${MY_BRANCH_BASE_MAJOR}.${MY_BRANCH_BASE_MINOR}!"
                            rm -f ${WORKSPACE}/XB_VERSION-${BUILD_NUMBER}
                            exit 1
                        fi
                        rm -f ${WORKSPACE}/XB_VERSION-${BUILD_NUMBER}
                    '''
                    git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '24e68886-c552-4033-8503-ed85bbaa31f3', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                        sh '''
                            # sudo is needed for better node recovery after compilation failure
                            # if building failed on compilation stage directory will have files owned by docker user
                            sudo git reset --hard
                            sudo git clean -xdf
                            cd pxb/v2
                            sudo rm -rf sources
                            ./local/checkout

                            aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0
                            echo Build: \$(date -u "+%s")
                            sg docker -c "
                                if [ \$(docker ps -q | wc -l) -ne 0 ]; then
                                    docker ps -q | xargs docker stop --time 1 || :
                                fi
                                ./docker/run-build ${DOCKER_OS}
                            " 2>&1 | tee build.log

                            echo Archive build: \$(date -u "+%s")
                            sed -i -e '
                                s^/tmp/pxb/^sources/^;
                                s^/tmp/results/^sources/^;
                            ' build.log
                            gzip build.log

                            if [[ -f build.log.gz ]]; then
                                until aws s3 cp --no-progress --acl public-read build.log.gz s3://pxb-build-cache/${BUILD_TAG}/build.log.gz; do
                                    sleep 5
                                done
                            fi

                            if [[ -f \$(ls sources/results/*.tar.gz | head -1) ]]; then
                                until aws s3 cp --no-progress --acl public-read sources/results/*.tar.gz s3://pxb-build-cache/${BUILD_TAG}/; do
                                    sleep 5
                                done
                            else
                                echo cannot find compiled archive
                                exit 1
                            fi
                            echo ${BUILD_TAG} > ${WORKSPACE}/COMPILE_BUILD_TAG
                        '''
                        archiveArtifacts artifacts: 'COMPILE_BUILD_TAG', followSymlinks: false, onlyIfSuccessful: true
                    }
                }
            }
        }
        stage('Archive Build') {
            agent { label 'micro-amazon' }
            steps {
                timeout(time: 60, unit: 'MINUTES')  {
                    retry(3) {
                        deleteDir()
                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '24e68886-c552-4033-8503-ed85bbaa31f3', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            sh '''
                                aws s3 cp --no-progress s3://pxb-build-cache/${BUILD_TAG}/build.log.gz ./build.log.gz
                                gunzip < build.log.gz > build.log
                                echo "
                                    binary    - https://s3.us-east-2.amazonaws.com/pxb-build-cache/${BUILD_TAG}/binary.tar.gz
                                    build log - https://s3.us-east-2.amazonaws.com/pxb-build-cache/${BUILD_TAG}/build.log.gz
                                " > public_url
                            '''
                        }
                        recordIssues enabledForFailure: true, tools: [gcc(pattern: 'build.log')]
                        archiveArtifacts 'build.log.gz,public_url'
                    }
                }
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

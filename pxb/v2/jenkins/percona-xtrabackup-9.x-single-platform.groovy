// Default to Hetzner
String LABEL = 'docker-x64'
String MICRO_LABEL = 'launcher-x64'

if (params.CLOUD == 'AWS') {
    LABEL = 'docker-32gb'
    MICRO_LABEL = 'micro-amazon'
}

pipeline {
    parameters {
        string(
            defaultValue: 'https://github.com/percona/percona-xtrabackup',
            description: 'URL to PXB repository',
            name: 'GIT_REPO',
            trim: true)
        string(
            defaultValue: 'trunk',
            description: 'Tag/Branch for PXB repository',
            name: 'BRANCH',
            trim: true)
        choice(
            choices: 'oraclelinux:9\nubuntu:jammy\nubuntu:noble\ndebian:bookworm\ndebian:trixie',
            description: 'OS version for compilation and testing',
            name: 'DOCKER_OS')
        choice(
            choices: 'both\ninnodb9x\nxtradb9x',
            description: 'MySQL server flavour for QA run (both runs innodb9x then xtradb9x)',
            name: 'XTRABACKUP_TARGET')
        string(
            defaultValue: '9.6.0',
            description: 'Version of MySQL InnoDB which will be used for bootstrap.sh script',
            name: 'INNODB9X_VERSION')
        string(
            defaultValue: '9.6.0-1',
            description: 'Version of Percona XtraDB which will be used for bootstrap.sh script',
            name: 'XTRADB9X_VERSION')
        string(
            defaultValue: '',
            description: 'Pass an URL for downloading bootstrap.sh, If empty will use from repository you specified',
            name: 'BOOTSTRAP_URL')
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
        booleanParam(
            defaultValue: false,
            description: 'Starts Microsoft Azurite emulator and tests xbcloud against it',
            name: 'WITH_AZURITE')
        booleanParam(
            name: 'WITH_XBCLOUD_TESTS',
            defaultValue: false,
            description: 'Run xbcloud tests')
        booleanParam(
            name: 'WITH_VAULT_TESTS',
            defaultValue: false,
            description: 'Run vault tests')
        booleanParam(
            name: 'WITH_KMIP_TESTS',
            defaultValue: false,
            description: 'Run kmip tests')
        choice(
            choices: 'Hetzner\nAWS',
            description: 'Host provider for Jenkins workers',
            name: 'CLOUD')
    }
    agent {
        label MICRO_LABEL
    }
    options {
        skipDefaultCheckout()
        skipStagesAfterUnstable()
        timeout(time: 6, unit: 'DAYS')
        buildDiscarder(logRotator(numToKeepStr: '200', artifactNumToKeepStr: '200'))
    }
    stages {
        stage('Build PXB 9.x') {
            agent { label LABEL }
            steps {
                timeout(time: 60, unit: 'MINUTES') {
                    script {
                        currentBuild.displayName = "${BUILD_NUMBER} ${CMAKE_BUILD_TYPE}/${DOCKER_OS}"
                    }
                    sh 'echo Prepare: \$(date -u "+%s")'
                    echo 'Checking Percona XtraBackup branch version'
                    sh '''#!/bin/bash
                        MY_BRANCH_BASE_MAJOR=9
                        RAW_VERSION_LINK=$(echo ${GIT_REPO%.git} | sed -e "s:github.com:raw.githubusercontent.com:g")
                        curl ${RAW_VERSION_LINK}/${BRANCH}/XB_VERSION --output ${WORKSPACE}/XB_VERSION-${BUILD_NUMBER}
                        source ${WORKSPACE}/XB_VERSION-${BUILD_NUMBER}
                        if [[ ${XB_VERSION_MAJOR} -lt ${MY_BRANCH_BASE_MAJOR} ]]; then
                            echo "Are you trying to build wrong branch?"
                            echo "You are trying to build ${XB_VERSION_MAJOR}.${XB_VERSION_MINOR} instead of ${MY_BRANCH_BASE_MAJOR}.x!"
                            rm -f ${WORKSPACE}/XB_VERSION-${BUILD_NUMBER}
                            exit 1
                        fi
                        rm -f ${WORKSPACE}/XB_VERSION-${BUILD_NUMBER}
                    '''
                    git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '24e68886-c552-4033-8503-ed85bbaa31f3', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                        sh '''#!/bin/bash
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

                            if [[ -f \$(ls sources/results/*.tar.gz | head -1) ]]; then
                                until aws s3 cp --no-progress --acl public-read sources/results/*.tar.gz s3://pxb-build-cache/${BUILD_TAG}/pxb9x.tar.gz; do
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
        stage('Test PXB 9.x') {
            agent { label LABEL }
            steps {
                timeout(time: 480, unit: 'MINUTES') {
                    git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '24e68886-c552-4033-8503-ed85bbaa31f3', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                        sh '''#!/bin/bash
                            sudo git reset --hard
                            sudo git clean -xdf
                            cd pxb/v2
                            sudo rm -rf sources/results
                            mkdir -p sources/results

                            until aws s3 cp --no-progress s3://pxb-build-cache/${BUILD_TAG}/pxb9x.tar.gz ./sources/results/binary.tar.gz; do
                                sleep 5
                            done

                            aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                            run_test_for_target() {
                                local target=$1
                                echo "Testing target: ${target} at $(date -u '+%s')"
                                export XTRABACKUP_TARGET="${target}"
                                sg docker -c "
                                    if [ \$(docker ps -q | wc -l) -ne 0 ]; then
                                        docker ps -q | xargs docker stop --time 1 || :
                                        docker rm --force azurite || :
                                    fi
                                    ulimit -a
                                    ./docker/run-test ${DOCKER_OS}
                                "
                            }

                            if [[ "${XTRABACKUP_TARGET}" == "both" ]]; then
                                run_test_for_target innodb9x
                                run_test_for_target xtradb9x
                            else
                                run_test_for_target "${XTRABACKUP_TARGET}"
                            fi

                            echo Archive test: \$(date -u "+%s")
                            gzip sources/results/* || true
                            if [[ -d sources/results/results/ ]]; then
                                tar -zcvf results.tar.gz sources/results/results/
                                mv results.tar.gz sources/results/
                            fi
                        '''
                    }
                }
            }
            post {
                always {
                    sh '''
                        cd pxb/v2
                        if [[ -f sources/results/junit.xml.gz ]]; then
                            gunzip < sources/results/junit.xml.gz > ${WORKSPACE}/junit.xml || true
                        elif [[ -f sources/results/junit.xml ]]; then
                            cp sources/results/junit.xml ${WORKSPACE}/junit.xml || true
                        fi
                    '''
                    step([$class: 'JUnitResultArchiver', testResults: 'junit.xml', healthScaleFactor: 1.0])
                    archiveArtifacts allowEmptyArchive: true, artifacts: 'junit.xml'
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

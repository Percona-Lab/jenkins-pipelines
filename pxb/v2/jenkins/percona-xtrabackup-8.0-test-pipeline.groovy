pipeline {
    parameters {
        choice(
            choices: 'centos:7\ncentos:8\noraclelinux:9\nubuntu:bionic\nubuntu:focal\nubuntu:jammy\ndebian:buster\ndebian:bullseye\nasan',
            description: 'OS version for compilation',
            name: 'DOCKER_OS')
        choice(
            choices: 'RelWithDebInfo\nDebug',
            description: 'Type of build to produce',
            name: 'CMAKE_BUILD_TYPE')
        choice(
            choices: 'innodb80\nxtradb80',
            description: 'MySQL version for QA run',
            name: 'XTRABACKUP_TARGET')
        string(
            defaultValue: '8.0.21',
            description: 'Version of MySQL InnoDB80 which will be used for bootstrap.sh script',
            name: 'INNODB80_VERSION')
        string(
            defaultValue: '8.0.20-11',
            description: 'Version of Percona XtraDB80 which will be used for bootstrap.sh script',
            name: 'XTRADB80_VERSION')
        string(
            defaultValue: '',
            description: './run.sh options, for options like: -j N Run tests in N parallel processes, -T seconds, -x options  Extra options to pass to xtrabackup',
            name: 'XBTR_ARGS')
        string(
            defaultValue: '',
            description: 'Pass an URL for downloading bootstrap.sh, If empty will use from repository you specified in PXB24_REPO',
            name: 'BOOTSTRAP_URL')
        choice(
            choices: 'OFF\nON',
            description: 'Starts Microsoft Azurite emulator and tests xbcloud against it',
            name: 'WITH_AZURITE')
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
        timeout(time: 10, unit: 'HOURS')
        buildDiscarder(logRotator(numToKeepStr: '200', artifactNumToKeepStr: '200'))
    }
    stages {
        stage('Test') {
            agent { label LABEL }
            steps {
                timeout(time: 240, unit: 'MINUTES')  {
                    script {
                        currentBuild.displayName = "${BUILD_NUMBER} ${CMAKE_BUILD_TYPE}/${DOCKER_OS}"
                    }
                    sh 'echo Prepare: \$(date -u "+%s")'
                    git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                    sh '''
                        # sudo is needed for better node recovery after compilation failure
                        # if building failed on compilation stage directory will have files owned by docker user
                        sudo git reset --hard
                        sudo git clean -xdf
                        cd pxb/v2
                        rm -rf sources/results
                        sudo git -C sources reset --hard || :
                        sudo git -C sources clean -xdf   || :
                        '''
                    copyArtifacts filter: 'COMPILE_BUILD_TAG', projectName: 'percona-xtrabackup-8.0-compile-param', selector: lastSuccessful()
                    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '24e68886-c552-4033-8503-ed85bbaa31f3', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                        sh '''
                            #!/bin/bash
                            for file in $(find . -name "COMPILE_BUILD_TAG"); do
                                COMPILE_BUILD_TAG_VAR+=" $(cat $file)"
                            done

                            for tarball in $(echo $COMPILE_BUILD_TAG_VAR); do
                                if [[ $CMAKE_BUILD_TYPE == "Debug" ]] && [[ ${DOCKER_OS} != "asan" ]]; then
                                    TARBALL=$(aws s3 ls pxb-build-cache/$tarball/ | grep x86_64-${DOCKER_OS//:/-}-debug | awk {'print $4'})
                                    if [[ ! -z $TARBALL ]]; then
                                        break
                                    fi
                                elif [[ $CMAKE_BUILD_TYPE == "RelWithDebInfo" ]] && [[ ${DOCKER_OS} != "asan" ]]; then
                                    TARBALL+=$(aws s3 ls pxb-build-cache/$tarball/ | grep x86_64-${DOCKER_OS//:/-}.tar.gz | awk {'print $4'})
                                    if [[ ! -z $TARBALL ]]; then
                                        break
                                    fi
                                elif [[ $CMAKE_BUILD_TYPE == "Debug" ]] && [[ ${DOCKER_OS} == "asan" ]]; then
                                    TARBALL+=$(aws s3 ls pxb-build-cache/$tarball/ | grep x86_64-${DOCKER_OS//:/-}-asan-debug | awk {'print $4'})
                                    if [[ ! -z $TARBALL ]]; then
                                        break
                                    fi
                                elif [[ $CMAKE_BUILD_TYPE == "RelWithDebInfo" ]] && [[ ${DOCKER_OS} == "asan" ]]; then
                                    TARBALL+=$(aws s3 ls pxb-build-cache/$tarball/ | grep x86_64-${DOCKER_OS//:/-}-asan | awk {'print $4'})
                                    if [[ ! -z $TARBALL ]]; then
                                        break
                                    fi
                                fi
                            done
                            PATH_TO_TARBALL=$tarball

                            cd pxb/v2

                            until aws s3 cp --no-progress s3://pxb-build-cache/$PATH_TO_TARBALL/$TARBALL ./sources/results/binary.tar.gz; do
                                sleep 5
                            done
                            aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0
                            echo Test: \$(date -u "+%s")
                            sg docker -c "
                                if [ \$(docker ps -q | wc -l) -ne 0 ]; then
                                    docker ps -q | xargs docker stop --time 1 || :
                                    docker rm --force azurite || :
                                fi
                                ulimit -a
                                ./docker/run-test ${DOCKER_OS}
                            "
                            echo Archive test: \$(date -u "+%s")
                            gzip sources/results/* || true
                            if [[ -d sources/results/results/ ]]; then
                                tar -zcvf results.tar.gz sources/results/results/
                                mv results.tar.gz sources/results/
                            fi
                            until aws s3 sync --no-progress --acl public-read --exclude 'binary.tar.gz' ./sources/results/ s3://pxb-build-cache/${BUILD_TAG}/; do
                                sleep 5
                            done
                        '''
                    }
                }
            }
        }
        stage('Archive Test Results') {
            agent { label 'micro-amazon' }
            steps {
                retry(3) {
                deleteDir()
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '24e68886-c552-4033-8503-ed85bbaa31f3', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        aws s3 cp --no-progress s3://pxb-build-cache/${BUILD_TAG}/xbtr.output.gz ./ || true
                        aws s3 cp --no-progress s3://pxb-build-cache/${BUILD_TAG}/junit.xml.gz ./ || true
                        aws s3 cp --no-progress s3://pxb-build-cache/${BUILD_TAG}/test_results.subunit.gz ./ || true
                        aws s3 cp --no-progress s3://pxb-build-cache/${BUILD_TAG}/results.tar.gz ./ || true
                        gunzip < xbtr.output.gz > xbtr.output || true
                        gunzip < junit.xml.gz > junit.xml || true
                        gunzip < test_results.subunit.gz > test_results.subunit || true
                    '''
                }
                archiveArtifacts allowEmptyArchive: true, followSymlinks: false, onlyIfSuccessful: true, artifacts: 'xbtr.output,junit.xml,test_results.subunit,results.tar.gz'
                step([$class: 'JUnitResultArchiver', testResults: 'junit.xml', healthScaleFactor: 1.0])
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

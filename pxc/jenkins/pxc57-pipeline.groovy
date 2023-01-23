def JENKINS_SCRIPTS_BRANCH = 'master'
def JENKINS_SCRIPTS_REPO = 'https://github.com/Percona-Lab/jenkins-pipelines'

pipeline {
    parameters {
        string(
            defaultValue: 'https://github.com/percona/percona-xtradb-cluster',
            description: 'URL to PXC repository',
            name: 'PXC57_REPO',
            trim: true)
        string(
            defaultValue: '5.7',
            description: 'Tag/PR/Branch for PXC repository',
            name: 'PXC57_BRANCH',
            trim: true)
        booleanParam(
            defaultValue: false, 
            description: 'Check only if you pass PR number to PXC57_BRANCH field',
            name: 'USE_PR') 
        string(
            defaultValue: 'https://github.com/percona/percona-xtrabackup',
            description: 'URL to PXB24 repository',
            name: 'PXB24_REPO',
            trim: true)
        string(
            defaultValue: 'percona-xtrabackup-2.4.27',
            description: 'Tag/Branch for PXC repository',
            name: 'PXB24_BRANCH',
            trim: true)
        choice(
            choices: 'centos:7\ncentos:8\noraclelinux:9\nubuntu:bionic\nubuntu:focal\nubuntu:jammy\ndebian:buster\ndebian:bullseye',
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
        choice(
            choices: 'yes\nno',
            description: 'Run mysql-test-run.pl',
            name: 'DEFAULT_TESTING')
        choice(
            choices: 'no\nyes',
            description: 'Build with ASAN',
            name: 'WITH_ASAN')
		string(
		    defaultValue: '4',
		    description: 'mtr can start n parallel server and distrbute workload among them. More parallelism is better but extra parallelism (beyond CPU power) will have less effect. This value is used for all test suites except Galera specific suites.',
		    name: 'PARALLEL_RUN')
		string(
			defaultValue: '2',
			description: 'mtr can start n parallel server and distrbute workload among them. More parallelism is better but extra parallelism (beyond CPU power) will have less effect. This value is used for the Galera specific test suites.',
			name: 'GALERA_PARALLEL_RUN')
	    choice(
	        choices: 'yes\nno',
	        description: 'Run mtr suites based on variable MTR_SUITES if the value is `no`. Otherwise the full mtr will be perfomed.',
	        name: 'FULL_MTR')
	    string(
	        defaultValue: 'galera,galera_3nodes,sys_vars',
	        description: 'mysql-test-run.pl suite names',
	        name: 'MTR_SUITES')
        string(
            defaultValue: '--unit-tests-report --big-test',
            description: 'mysql-test-run.pl options, for options like: --big-test --only-big-test --nounit-tests --unit-tests-report',
            name: 'MTR_ARGS')
        string(
            defaultValue: '1',
            description: 'Run each test N number of times, --repeat=N',
            name: 'MTR_REPEAT')
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
        stage('Prepare') {
            steps {
                script {
                    currentBuild.displayName = "${BUILD_NUMBER} ${CMAKE_BUILD_TYPE}/${DOCKER_OS}"
                }

                sh 'echo Prepare: \$(date -u "+%s")'
                echo 'Checking PXC branch version'
                sh '''
                    MY_BRANCH_BASE_MAJOR=5
                    MY_BRANCH_BASE_MINOR=7

                    if [[ ${USE_PR} == "true" ]]; then
                        if [ -f /usr/bin/yum ]; then
                            sudo yum -y install jq
                        else
                            sudo apt-get install -y jq
                        fi

                        PXC57_REPO=$(curl https://api.github.com/repos/percona/percona-xtradb-cluster/pulls/${PXC57_BRANCH} | jq -r '.head.repo.html_url')
                        PXC57_BRANCH=$(curl https://api.github.com/repos/percona/percona-xtradb-cluster/pulls/${PXC57_BRANCH} | jq -r '.head.ref')
                    fi

                    RAW_VERSION_LINK=$(echo ${PXC57_REPO%.git} | sed -e "s:github.com:raw.githubusercontent.com:g")
                    REPLY=$(curl -Is ${RAW_VERSION_LINK}/${PXC57_BRANCH}/MYSQL_VERSION | head -n 1 | awk '{print $2}')
                    if [[ ${REPLY} != 200 ]]; then
                        wget ${RAW_VERSION_LINK}/${PXC57_BRANCH}/VERSION -O ${WORKSPACE}/VERSION-${BUILD_NUMBER}
                    else
                        wget ${RAW_VERSION_LINK}/${PXC57_BRANCH}/MYSQL_VERSION -O ${WORKSPACE}/VERSION-${BUILD_NUMBER}
                    fi
                    source ${WORKSPACE}/VERSION-${BUILD_NUMBER}
                    if [[ ${MYSQL_VERSION_MAJOR} -lt ${MY_BRANCH_BASE_MAJOR} ]] ; then
                        echo "Are you trying to build wrong branch?"
                        echo "You are trying to build ${MYSQL_VERSION_MAJOR}.${MYSQL_VERSION_MINOR} instead of ${MY_BRANCH_BASE_MAJOR}.${MY_BRANCH_BASE_MINOR}!"
                        rm -f ${WORKSPACE}/VERSION-${BUILD_NUMBER}
                        exit 1
                    fi
                    rm -f ${WORKSPACE}/VERSION-${BUILD_NUMBER}
                '''
            }
        }
        stage('Check out and Build PXB') {
            parallel {
                stage('Build PXB24') {
                    agent { label 'docker' }
                    steps {
                        git branch: JENKINS_SCRIPTS_BRANCH, url: JENKINS_SCRIPTS_REPO
                        echo 'Checkout PXB24 sources'
                        sh '''
                            # sudo is needed for better node recovery after compilation failure
                            # if building failed on compilation stage directory will have files owned by docker user
                            sudo git reset --hard
                            sudo git clean -xdf
                            sudo rm -rf sources
                            ./pxc/local/checkout57 PXB24
                        '''
                        echo 'Build PXB24'
                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'c42456e5-c28d-4962-b32c-b75d161bff27', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            sh '''
                                aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0
                                sg docker -c "
                                    if [ \$(docker ps -q | wc -l) -ne 0 ]; then
                                        docker ps -q | xargs docker stop --time 1 || :
                                    fi
                                    ./pxc/docker/run-build-pxb24 ${DOCKER_OS}
                                " 2>&1 | tee build.log
                             
                                if [[ -f \$(ls pxc/sources/pxb24/results/*.tar.gz | head -1) ]]; then
                                    until aws s3 cp --no-progress --acl public-read pxc/sources/pxb24/results/*.tar.gz s3://pxc-build-cache/${BUILD_TAG}/pxb24.tar.gz; do
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
        stage('Build PXC57') {
                agent { label 'docker-32gb' }
                steps {
                    git branch: JENKINS_SCRIPTS_BRANCH, url: JENKINS_SCRIPTS_REPO
                    echo 'Checkout PXC57 sources'
                    sh '''
                        # sudo is needed for better node recovery after compilation failure
                        # if building failed on compilation stage directory will have files owned by docker user
                        sudo git reset --hard
                        sudo git clean -xdf
                        sudo rm -rf sources
                        ./pxc/local/checkout57 PXC57
                    '''

                    echo 'Build PXC57'
                    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'c42456e5-c28d-4962-b32c-b75d161bff27', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                        sh '''							
                            aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0
                            sg docker -c "
                                if [ \$(docker ps -q | wc -l) -ne 0 ]; then
                                    docker ps -q | xargs docker stop --time 1 || :
                                fi
                                ./pxc/docker/run-build-pxc57 ${DOCKER_OS}
                            " 2>&1 | tee build.log
                          
                            if [[ -f \$(ls pxc/sources/pxc57/results/*.tar.gz | head -1) ]]; then
                                until aws s3 cp --no-progress --acl public-read pxc/sources/pxc57/results/*.tar.gz s3://pxc-build-cache/${BUILD_TAG}/pxc57.tar.gz; do
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
        stage('Test PXC57') {
                agent { label 'docker-32gb' }
                steps {
                    git branch: JENKINS_SCRIPTS_BRANCH, url: JENKINS_SCRIPTS_REPO
                    echo 'Test PXC57'
                    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'c42456e5-c28d-4962-b32c-b75d161bff27', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                        sh '''
                            sudo git reset --hard
                            sudo git clean -xdf
                            rm -rf pxc/sources/* || :
                            sudo git -C sources reset --hard || :
                            sudo git -C sources clean -xdf   || :

                            until aws s3 cp --no-progress s3://pxc-build-cache/${BUILD_TAG}/pxb24.tar.gz ./pxc/sources/pxc/results/pxb24.tar.gz; do
                                sleep 5
                            done
                            until aws s3 cp --no-progress s3://pxc-build-cache/${BUILD_TAG}/pxc57.tar.gz ./pxc/sources/pxc/results/pxc57.tar.gz; do
                                sleep 5
                            done

                            aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0
                            sg docker -c "
                                if [ \$(docker ps -q | wc -l) -ne 0 ]; then
                                    docker ps -q | xargs docker stop --time 1 || :
                                fi
                                ./pxc/docker/run-test57 ${DOCKER_OS}
                            "
                        '''
                    }
                    step([$class: 'JUnitResultArchiver', testResults: 'pxc/sources/pxc/results/*.xml', healthScaleFactor: 1.0])
                    archiveArtifacts 'pxc/sources/pxc/results/*.xml,pxc/sources/pxc/results/pxc57-test-mtr_logs.tar.gz'
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

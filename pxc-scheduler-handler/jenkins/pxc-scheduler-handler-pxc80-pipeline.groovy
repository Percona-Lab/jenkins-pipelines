pipeline_timeout = 10

pipeline {
    parameters {
        string(
            name: 'GIT_REPO',
            defaultValue: 'https://github.com/percona/pxc_scheduler_handler.git',
            description: 'URL to the scheduler handler repository',
            trim: true)
        string(
            name: 'BRANCH',
            defaultValue: 'main',
            description: 'Tag/Branch for the scheduler repository',
            trim: true)
        string(
            name: 'PXC_TARBALL',
            defaultValue: 'https://downloads.percona.com/downloads/Percona-XtraDB-Cluster-LATEST/Percona-XtraDB-Cluster-8.0.23/binary/tarball/Percona-XtraDB-Cluster_8.0.23-14.1_Linux.x86_64.glibc2.17.tar.gz',
            description: 'PXC tarball including mtr to be used for testing',
            trim: true)
        string(
            name: 'MTR_ARGS',
            defaultValue: '--suite=proxysql',
            description: 'mysql-test-run.pl options, for options like: --big-test --only-big-test --nounit-tests --unit-tests-report')
        string(
            name: 'MTR_REPEAT',
            defaultValue: '1',
            description: 'Run each test N number of times, --repeat=N')
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
        stage('Build Scheduler') {
            agent { label 'docker' }
            steps {
                git branch: 'pxc-scheduler-handler-1', url: 'https://github.com/kamil-holubicki/jenkins-pipelines'
                echo 'Checkout pxc_scheduler_handler sources'
                sh '''
                    # sudo is needed for better node recovery after compilation failure
                    # if building failed on compilation stage directory will have files owned by docker user
                    sudo git reset --hard
                    sudo git clean -xdf
                    sudo rm -rf sources
                    ./pxc-scheduler-handler/local/checkout
                '''

                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'c42456e5-c28d-4962-b32c-b75d161bff27', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        echo 'Build pxc_scheduler_handler'
                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0
                        sg docker -c "
                            if [ \$(docker ps -q | wc -l) -ne 0 ]; then
                                docker ps -q | xargs docker stop --time 1 || :
                            fi
                            ./pxc-scheduler-handler/docker/run-build ubuntu:focal
                        " 2>&1 | tee build.log

                        if [[ -f \$(ls pxc-scheduler-handler/sources/pxc_scheduler_handler/results/*.tar.gz | head -1) ]]; then
                            until aws s3 cp --no-progress --acl public-read pxc-scheduler-handler/sources/pxc_scheduler_handler/results/*.tar.gz s3://proxysql-scheduler-build-cache/${BUILD_TAG}/pxc_scheduler_handler.tar.gz; do
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

        stage('Test Scheduler') {
            agent { label 'docker-32gb' }
            steps {
                git branch: 'pxc-scheduler-handler-1', url: 'https://github.com/kamil-holubicki/jenkins-pipelines'
                echo 'Test pxc_scheduler_handler'
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'c42456e5-c28d-4962-b32c-b75d161bff27', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        until aws s3 cp --no-progress s3://proxysql-scheduler-build-cache/${BUILD_TAG}/pxc_scheduler_handler.tar.gz ./pxc-scheduler-handler/sources/pxc_scheduler_handler/results/pxc_scheduler_handler.tar.gz; do
                            sleep 5
                        done

                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0
                        sg docker -c "
                            if [ \$(docker ps -q | wc -l) -ne 0 ]; then
                                docker ps -q | xargs docker stop --time 1 || :
                            fi
                            ./pxc-scheduler-handler/docker/run-test ubuntu:focal
                        "
                    '''
                }
                step([$class: 'JUnitResultArchiver', testResults: 'pxc-scheduler-handler/sources/pxc_scheduler_handler/results/*.xml', healthScaleFactor: 1.0])
                archiveArtifacts 'pxc-scheduler-handler/sources/pxc-scheduler-handler/results/*.xml,pxc-scheduler-handler/sources/pxc_scheduler_handler/results/pxc-scheduler-handler-test-mtr_logs.tar.gz'
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

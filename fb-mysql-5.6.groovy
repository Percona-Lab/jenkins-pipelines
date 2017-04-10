void build(String CMAKE_BUILD_TYPE) {
    popArtifactDir(CMAKE_BUILD_TYPE)
    sh """
        if [ -f '${CMAKE_BUILD_TYPE}/sql/mysqld' -a '${FORCE_REBULD}' = 'false' ]; then
            echo Skip building
        else
            mkdir ${CMAKE_BUILD_TYPE} || :
            pushd ${CMAKE_BUILD_TYPE}
                export WITH_ZSTD=\$(pwd -P)/../zstd-1.1.3/lib
                cmake .. \
                    -DCMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE} \
                    -DWITH_SSL=system \
                    -DWITH_ZLIB=bundled \
                    -DMYSQL_MAINTAINER_MODE=0 \
                    -DENABLED_LOCAL_INFILE=1 \
                    -DENABLE_DTRACE=0 \
                    -DCMAKE_CXX_FLAGS='-march=native'
                make -j8
            popd
        fi
    """
    pushArtifactDir(CMAKE_BUILD_TYPE)
}

void runMTR(String CMAKE_BUILD_TYPE) {
    sh """
        pushd ${CMAKE_BUILD_TYPE}/mysql-test
            if [ -f 'mtr.log' -a '${FORCE_RETEST}' = 'false' ]; then
                echo Skip mtr
            else
                export LD_PRELOAD=\$(
                    ls /usr/local/lib/libeatmydata.so \
                        /usr/lib64/libeatmydata.so \
                        /usr/lib/libeatmydata.so \
                        /usr/lib/libeatmydata/libeatmydata.so \
                        2>/dev/null \
                        | tail -1
                )
                perl mysql-test-run.pl \
                    --async-client \
                    --parallel=16 \
                    --fast \
                    --max-test-fail=1000 \
                    --retry=0 \
                    --force \
                    --mysqld=--rocksdb \
                    --mysqld=--default-storage-engine=rocksdb \
                    --mysqld=--skip-innodb \
                    --mysqld=--default-tmp-storage-engine=MyISAM \
                    --suite=rocksdb \
                    --testcase-timeout=1200 \
                    --skip-test=rocksdb.add_index_inplace_sstfilewriter \
                    | tee mtr.log \
                    || :
            fi
        popd
        cp ${CMAKE_BUILD_TYPE}/mysql-test/mtr.log \
            mtr-${CMAKE_BUILD_TYPE}.log
        perl -ane '
            if (m{\\[ (fail|pass|skipped|disabled) \\]}) {
                \$i++;
                s/(.*?)(?:w\\d+)? \\[ (pass|fail) \\]/\$2 \$i - \$1/;
                s/(.*?)(?:w\\d+)? \\[ (skipped|disabled) \\]/ok \$i \\# \$2: \$1/;
                s/skipped/SKIP/;
                s/disabled/SKIP/;
                s/pass/ok/;
                s/fail/not ok/;
                print;
            }
            END { print "1..\$i\\n" }
        ' mtr-${CMAKE_BUILD_TYPE}.log | tee ${CMAKE_BUILD_TYPE}.tap
    """

    stash includes: "${CMAKE_BUILD_TYPE}.tap,mtr-${CMAKE_BUILD_TYPE}.log", name: "${CMAKE_BUILD_TYPE}"
    pushArtifactDir(CMAKE_BUILD_TYPE)
    archiveArtifacts "mtr-${CMAKE_BUILD_TYPE}.log"
}

void pushArtifactDir(String DIR_NAME) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            S3_PATH=s3://percona-jenkins-artifactory/\$JOB_NAME/\$(git rev-parse --short HEAD)/${DIR_NAME}
            aws s3 sync ${DIR_NAME}/sql/        \$S3_PATH/sql/        --exclude "*CMakeFiles/*" || :
            aws s3 sync ${DIR_NAME}/extra/      \$S3_PATH/extra/      --exclude "*CMakeFiles/*" || :
            aws s3 sync ${DIR_NAME}/client/     \$S3_PATH/client/     --exclude "*CMakeFiles/*" || :
            aws s3 sync ${DIR_NAME}/storage/    \$S3_PATH/storage/    --exclude "*CMakeFiles/*" || :
            aws s3 sync ${DIR_NAME}/mysql-test/ \$S3_PATH/mysql-test/ --exclude "*CMakeFiles/*" --exclude "*var/*" || :
        """
    }
}

void popArtifactDir(String DIR_NAME) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            S3_PATH=s3://percona-jenkins-artifactory/\$JOB_NAME/\$(git rev-parse --short HEAD)/${DIR_NAME}
            aws s3 sync \$S3_PATH/ ${DIR_NAME}/ --exclude "*CMakeFiles/*" --exclude "*var/*" || :
            chmod -R 755 ${DIR_NAME}
        """
    }
}

pipeline {
    environment {
        specName = 'fb-mysql-5.6'
        repo     = 'facebook/mysql-5.6'
    }
    agent {
        label 'centos7-64'
    }
    parameters {
        string(
            defaultValue: 'fb-mysql-5.6.35',
            description: '',
            name: 'GIT_BRANCH')
        booleanParam(
            defaultValue: false,
            description: '',
            name: 'FORCE_REBULD')
        booleanParam(
            defaultValue: false,
            description: '',
            name: 'FORCE_RETEST')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    triggers {
        pollSCM '*/10 * * * *'
    }

    stages {
        stage('Fetch sources') {
            steps {
                slackSend channel: '#fb-myrocks-build', color: '#FFFF00', message: "[${specName}]: build started - ${env.BUILD_URL}"

                deleteDir()
                git poll: true, branch: GIT_BRANCH, url: "https://github.com/${repo}.git"
                sh '''
                    git submodule init
                    git submodule update
                '''
            }
        }

        stage('Build zstd') {
            steps {
                sh '''
                    ZSTD_VERSION=1.1.3
                    wget https://github.com/facebook/zstd/archive/v$ZSTD_VERSION.tar.gz
                    tar zxpf v$ZSTD_VERSION.tar.gz
                    pushd zstd-$ZSTD_VERSION
                        cmake build/cmake -DACTIVATE_POSITION_INDEPENDENT_CODE_FLAG=on
                        make
                        cp lib/zstd.h ../include/
                        cp lib/libzstd.a lib/libzstd_pic.a
                    popd
                '''
            }
        }

        stage('Build') {
            steps {
                parallel(
                    "RelWithDebInfo": {
                        build('RelWithDebInfo')
                    },
                    "Debug": {
                        build('Debug')
                    },
                )
            }
        }

        stage('Run MTR') {
            steps {
                parallel(
                    "RelWithDebInfo": {
                        runMTR('RelWithDebInfo')
                    },
                    "Debug": {
                        runMTR('Debug')
                    },
                )
            }
        }
    }

    post {
        success {
            script {
                unstash 'Debug'
                COMPLETED = sh (
                    script: 'grep Completed: mtr-Debug.log | cut -d : -f 2',
                    returnStdout: true
                ).trim()
                step([$class: "TapPublisher", testResults: '*.tap'])
                slackSend channel: '#fb-myrocks-build', color: '#00FF00', message: "[${specName}]: build finished\n${COMPLETED}"
            }
        }
        failure {
            slackSend channel: '#fb-myrocks-build', color: '#FF0000', message: "[${specName}]: build failed"
        }
        always {
            deleteDir()
        }
    }
}

void build(String CMAKE_BUILD_TYPE) {
    popArtifactFile("${GIT_BRANCH}-${CMAKE_BUILD_TYPE}.tar.gz")
    sh """
        TARBALL=\$(pwd -P)/${GIT_BRANCH}-${CMAKE_BUILD_TYPE}.tar.gz
        if [ -f "\${TARBALL}" -a '${FORCE_REBUILD}' = 'false' ]; then
            echo Skip building
            mkdir -p ${CMAKE_BUILD_TYPE}/destdir/usr/local
            tar \
                -C ${CMAKE_BUILD_TYPE}/destdir/usr/local \
                -xzf \${TARBALL}
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
                    -DCMAKE_CXX_FLAGS='-march=native' \
                    -DCMAKE_INSTALL_PREFIX="/usr/local/${GIT_BRANCH}" \
                    -DMYSQL_DATADIR="/usr/local/${GIT_BRANCH}/data"
                make -j8

                export DESTDIR=destdir
                make install

                mkdir -p destdir/usr/local/${GIT_BRANCH}/rqg/rqg/common/
                cp -r \
                    ../rqg/rqg/common/mariadb-patches \
                    destdir/usr/local/${GIT_BRANCH}/rqg/rqg/common/mariadb-patches

                tar \
                    -C destdir/usr/local \
                    --owner=0 \
                    --group=0 \
                    -czf \${TARBALL} \
                    ${GIT_BRANCH}
            popd
        fi
    """
    archiveArtifacts "${GIT_BRANCH}-${CMAKE_BUILD_TYPE}.tar.gz"
    pushArtifactFile("${GIT_BRANCH}-${CMAKE_BUILD_TYPE}.tar.gz")
}

void runMTR(String CMAKE_BUILD_TYPE) {
    popArtifactFile("mtr-${CMAKE_BUILD_TYPE}.log")
    popArtifactFile("junit-${CMAKE_BUILD_TYPE}.xml")
    sh """
        ROOT_DIR=\$(pwd -P)
        if [ -f 'junit-${CMAKE_BUILD_TYPE}.xml' -a '${FORCE_RETEST}' = 'false' ]; then
            echo Skip mtr
        else
            export LD_LIBRARY_PATH=\$(pwd -P)/zstd-1.1.3/lib
            pushd ${CMAKE_BUILD_TYPE}/destdir/usr/local/${GIT_BRANCH}/mysql-test
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
                    --parallel=\$(grep -cw ^processor /proc/cpuinfo) \
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
                    --junit-output=\${ROOT_DIR}/junit-${CMAKE_BUILD_TYPE}.xml \
                    | tee \${ROOT_DIR}/mtr-${CMAKE_BUILD_TYPE}.log \
                    || :
            popd
        fi
    """
    stash includes:  "junit-${CMAKE_BUILD_TYPE}.xml,mtr-${CMAKE_BUILD_TYPE}.log", name: "${CMAKE_BUILD_TYPE}"

    archiveArtifacts "mtr-${CMAKE_BUILD_TYPE}.log"
    pushArtifactFile("mtr-${CMAKE_BUILD_TYPE}.log")

    archiveArtifacts "junit-${CMAKE_BUILD_TYPE}.xml"
    pushArtifactFile("junit-${CMAKE_BUILD_TYPE}.xml")

    script {
        def fileName = sh(returnStdout: true, script: "ls ${CMAKE_BUILD_TYPE}/destdir/usr/local/${GIT_BRANCH}/mysql-test/var/**/*.err || :").trim()
        if (fileName != "" && fileExists(fileName)) {
            archiveArtifacts "${CMAKE_BUILD_TYPE}/destdir/usr/local/${GIT_BRANCH}/mysql-test/var/**/*.err"
            archiveArtifacts "${CMAKE_BUILD_TYPE}/destdir/usr/local/${GIT_BRANCH}/mysql-test/var/**/*.log"
        }
    }
}

void pushArtifactFile(String FILE_NAME) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            S3_PATH=s3://percona-jenkins-artifactory/\$JOB_NAME/\$(git rev-parse --short HEAD)
            aws s3 ls \$S3_PATH/${FILE_NAME} || :
            aws s3 cp --quiet ${FILE_NAME} \$S3_PATH/${FILE_NAME} || :
        """
    }
}

void popArtifactFile(String FILE_NAME) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            S3_PATH=s3://percona-jenkins-artifactory/\$JOB_NAME/\$(git rev-parse --short HEAD)
            aws s3 cp --quiet \$S3_PATH/${FILE_NAME} ${FILE_NAME} || :
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
        string(
            defaultValue: 'false',
            description: '',
            name: 'FORCE_REBUILD')
        string(
            defaultValue: 'false',
            description: '',
            name: 'FORCE_RETEST')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(artifactNumToKeepStr: '5'))
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
        always {
            deleteDir()
        }
        success {
            script {
                unstash 'Debug'
                unstash 'RelWithDebInfo'
                COMPLETED = sh (
                    script: 'grep Completed: mtr-Debug.log | cut -d : -f 2',
                    returnStdout: true
                ).trim()
                slackSend channel: '#fb-myrocks-build', color: '#00FF00', message: "[${specName}]: build finished\n${COMPLETED}"
                step([$class: 'JUnitResultArchiver', testResults: 'junit-*.xml', healthScaleFactor: 1.0])
            }
        }
        failure {
            slackSend channel: '#fb-myrocks-build', color: '#FF0000', message: "[${specName}]: build failed"
        }
    }
}

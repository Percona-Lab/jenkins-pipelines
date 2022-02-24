pipeline_timeout = 10

pipeline {
    parameters {
        string(
            defaultValue: '8.0.26-17',
            description: 'PS tarball version to download for testing',
            name: 'VERSION',
            trim: true)
        choice(
            choices: 'centos:7\ncentos:8\nubuntu:bionic\nubuntu:focal\ndebian:buster',
            description: 'OS version for compilation',
            name: 'DOCKER_OS')
        string(
            defaultValue: '4',
            description: 'mtr can start n parallel server and distrbute workload among them. More parallelism is better but extra parallelism (beyond CPU power) will have less effect.',
            name: 'PARALLEL_RUN')
        string(
            defaultValue: 'main,innodb,auth_sec,clone,collations,connection_control,encryption,federated,funcs_2,gcol,gis,information_schema,innodb_fts,innodb_gis,innodb_undo,innodb_zip,opt_trace,parts,perfschema,query_rewrite_plugins,sys_vars, sysschema',
            description: 'mysql-test-run.pl suite names',
            name: 'MTR_SUITES')
        string(
            defaultValue: '--unit-tests-report',
            description: 'mysql-test-run.pl options, for options like: --big-test --only-big-test --nounit-tests --unit-tests-report',
            name: 'MTR_ARGS')
        string(
            defaultValue: '1',
            description: 'Retry failing MTR tests N number of times, --retry-failure=N',
            name: 'MTR_RETRY')
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
                echo 'Downloading PS tarball: \$(date -u "+%s")'
                sh '''
                    set -o pipefail
                    ROOT_FS=$(pwd)
                    sudo killall -9 mysqld || true

                    # Fetch the latest PS binaries
                    rm -rf $ROOT_FS/ps_latest || true
                    mkdir $ROOT_FS/ps_latest
                    cd $ROOT_FS/ps_latest

                    PS_TAR=Percona-Server-latest.tar.gz
                    wget -cO - https://downloads.percona.com/downloads/TESTING/ps-${VERSION}/Percona-Server-${VERSION}-Linux.x86_64.glibc2.17.tar.gz > ${PS_TAR}
                    tar -xzf ${PS_TAR}
                    rm *.tar.gz
                    mv Percona-Server-* Percona-Server-latest
                '''
            }
        }
        stage('Execute testcases') {
            steps {
                echo 'Starting MTR test suite execution: \$(date -u "+%s")'
                sh '''
                    set +e
                    exitcode=0

                    export PATH="${ROOT_FS}:${PATH}"
                    PS_BASE=$PWD/ps_latest/Percona-Server-latest
                    cd ${PS_BASE}/mysql-test
                    VAR_DIR=${PS_BASE}/ps80-mtr-test
                    rm -rf $VAR_DIR || true

                    if [ -z ${MTR_SUITES} ]; then
                      SUITE_OPT=""
                    else
                      SUITE_OPT="--suite=${MTR_SUITES}"
                    fi

                    # default suite
                    mkdir -p ${VAR_DIR}/var1
                    perl ./mysql-test-run.pl --vardir=${VAR_DIR}/var1 --parallel=${PARALLEL_RUN} \
                         --retry-failure=${MTR_RETRY} --max-test-fail=0 --force --suite-timeout=600 $SUITE_OPT ${MTR_ARGS} 2>&1 | tee -a mtr1.out

                    sudo killall -9 mysqld || true
                    exitcode1=`grep "\\[ fail \\]" mtr1.out | wc -l`
                    set -e

                    if [ $exitcode1 -gt 0 ];then
                      exit 1;
                    else
                      exit 0;
                    fi
                '''
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

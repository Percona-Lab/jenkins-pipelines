PIPELINE_TIMEOUT = 10
JENKINS_SCRIPTS_BRANCH = 'master'
JENKINS_SCRIPTS_REPO = 'https://github.com/Percona-Lab/jenkins-pipelines'
AWS_CREDENTIALS_ID = 'c42456e5-c28d-4962-b32c-b75d161bff27'
MAX_S3_RETRIES = 12
S3_ROOT_DIR = 's3://pxc-build-cache'
// boolean default is false, 1st item unused.
WORKER_ABORTED = new boolean[9]
BUILD_NUMBER_BINARIES_FOR_RERUN = 0
BUILD_TRIGGER_BY = ''
PXB24_PACKAGE_TO_DOWNLOAD = ''
PXB80_PACKAGE_TO_DOWNLOAD = ''

def LABEL = 'docker-32gb'

// We need this map to construct proper pxb tarball name
OsToGlibcMap = [
    "centos:7" : "2.17",
    "centos:8" : "2.28",
    "oraclelinux:9": "2.34",
    "oraclelinux:10": "2.39",
    "ubuntu:focal" : "2.31",
    "ubuntu:jammy" : "2.35",
    "ubuntu:noble" : "2.39",
    "debian:bullseye" : "2.31",
    "debian:bookworm" : "2.35",
    "debian:trixie" : "2.39" ]

void uploadFileToS3(String SRC_FILE_PATH, String DST_DIRECTORY, String DST_FILE_NAME) {
    echo "Upload ${SRC_FILE_PATH} file to S3 ${S3_ROOT_DIR}/${DST_DIRECTORY}/${DST_FILE_NAME}. Max retries: ${MAX_S3_RETRIES}"
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: AWS_CREDENTIALS_ID, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            retry=0
            S3_PATH=${S3_ROOT_DIR}/${DST_DIRECTORY}/${DST_FILE_NAME}
            until [ \$retry -eq ${MAX_S3_RETRIES} ] || aws s3 cp --no-progress --acl public-read ${SRC_FILE_PATH} \$S3_PATH; do
                sleep 5
                retry=\$((retry+1))
            done
        """
    }
}

void downloadFileFromS3(String SRC_DIRECTORY, String SRC_FILE_NAME, String DST_PATH) {
    echo "Downloading ${S3_ROOT_DIR}/${SRC_DIRECTORY}/${SRC_FILE_NAME} from S3 to ${DST_PATH} . Max retries: ${MAX_S3_RETRIES}"
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: AWS_CREDENTIALS_ID, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            retry=0
            S3_PATH=${S3_ROOT_DIR}/${SRC_DIRECTORY}/${SRC_FILE_NAME}
            until [ \$retry -eq ${MAX_S3_RETRIES} ] || aws s3 cp --no-progress \$S3_PATH ${DST_PATH}; do
                sleep 5
                retry=\$((retry+1))
            done
        """
    }
}

String getLatestPxbPackageName(String PXB_VER, String GLIBC_VER) {
    pxbLatestTag = sh (
    script: """
        echo \$(git -c 'versionsort.suffix=-' ls-remote --exit-code --refs --sort='v:refname' https://github.com/percona/percona-xtrabackup | grep percona-xtrabackup-${PXB_VER}.* | tail -1 | cut --delimiter='/' --fields=3)
        """,
        returnStdout: true
    ).trim()
    echo "====> PXB${PXB_VER} latest tag: ${pxbLatestTag}"

    // Try do download
    pxbDownloadable = sh (
    script: """
        echo \$(curl -Is https://downloads.percona.com/downloads/Percona-XtraBackup-${PXB_VER}/Percona-XtraBackup-\$(echo ${pxbLatestTag} | cut -d '-' -f 3,4)/binary/tarball/${pxbLatestTag}-Linux-x86_64.glibc${GLIBC_VER}.tar.gz | head -1 | awk {'print \$2'})
        """,
        returnStdout: true
    ).trim()
    echo "Status of PXB${PXB_VER} package is ${pxbDownloadable}"

    if (pxbDownloadable == '200') {
        return pxbLatestTag
    }
    return ''
}

String getGlibcVersion() {
    if (OsToGlibcMap[env.DOCKER_OS]) {
        return OsToGlibcMap[env.DOCKER_OS]
    }
    // Fallback to something, probably will not work anyway.
    return "2.35"
}

void checkIfPxbPackagesDownloadable() {
    if (env.PXB24_LATEST == "true") {
        PXB24_PACKAGE_TO_DOWNLOAD = getLatestPxbPackageName("2.4", "2.17")
    }
    if (env.PXB80_LATEST == "true") {
        PXB80_PACKAGE_TO_DOWNLOAD = getLatestPxbPackageName("8.0", getGlibcVersion())
    }
}

void downloadLatestPxbPackage(String PXB_VER, String PACKAGE_NAME, String GLIBC_VER, String OUTPUT_FILE_PATH) {
    echo "====> PXB${PXB_VER} latest tag: ${PACKAGE_NAME}"
    sh """
        echo "====> PXB${PXB_VER} package is availble for downloading from the site"
        wget https://downloads.percona.com/downloads/Percona-XtraBackup-${PXB_VER}/Percona-XtraBackup-\$(echo ${PACKAGE_NAME} | cut -d '-' -f 3,4)/binary/tarball/${PACKAGE_NAME}-Linux-x86_64.glibc${GLIBC_VER}-minimal.tar.gz -O ${OUTPUT_FILE_PATH}
    """
}

void downloadFilesForTests() {
    sh """
        mkdir -p ./pxc/sources/pxc/results/pxb24
        mkdir -p ./pxc/sources/pxc/results/pxb80
    """
    if (PXB24_PACKAGE_TO_DOWNLOAD != '') {
        downloadLatestPxbPackage("2.4", PXB24_PACKAGE_TO_DOWNLOAD, "2.17", "./pxc/sources/pxc/results/pxb24/pxb24.tar.gz")
    } else {
        downloadFileFromS3("${BUILD_TAG_BINARIES}", "pxb24.tar.gz", "./pxc/sources/pxc/results/pxb24/pxb24.tar.gz")
    }

    if (PXB80_PACKAGE_TO_DOWNLOAD) {
        downloadLatestPxbPackage("8.0", PXB80_PACKAGE_TO_DOWNLOAD, getGlibcVersion(), "./pxc/sources/pxc/results/pxb80/pxb80.tar.gz")
    } else {
        downloadFileFromS3("${BUILD_TAG_BINARIES}", "pxb80.tar.gz", "./pxc/sources/pxc/results/pxb80/pxb80.tar.gz")
    }

    downloadFileFromS3("${BUILD_TAG_BINARIES}", "pxc80.tar.gz", "./pxc/sources/pxc/results/pxc80.tar.gz")
}

void prepareWorkspace() {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: AWS_CREDENTIALS_ID, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            sudo git reset --hard
            sudo git clean -xdf
            rm -rf pxc/sources/* || :
            sudo git -C sources reset --hard || :
            sudo git -C sources clean -xdf   || :
        """
    }
}

void doTests(String WORKER_ID, String SUITES, String STANDALONE_TESTS = '', boolean UNIT_TESTS = false, boolean CIFS_TESTS = false) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: AWS_CREDENTIALS_ID, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            echo "Starting MTR worker ${WORKER_ID}"

            if [[ "${CIFS_TESTS}" == "true" ]]; then
                echo "Enabling CIFS tests"
                if [[ ! -f /mnt/ci_disk_${CMAKE_BUILD_TYPE}.img ]] && [[ -z \$(mount | grep /mnt/ci_disk_dir_${CMAKE_BUILD_TYPE}) ]]; then
                    sudo dd if=/dev/zero of=/mnt/ci_disk_${CMAKE_BUILD_TYPE}.img bs=1G count=10
                    sudo /sbin/mkfs.vfat /mnt/ci_disk_${CMAKE_BUILD_TYPE}.img
                    sudo mkdir -p /mnt/ci_disk_dir_${CMAKE_BUILD_TYPE}
                    sudo mount -o loop -o uid=27 -o gid=27 -o check=r /mnt/ci_disk_${CMAKE_BUILD_TYPE}.img /mnt/ci_disk_dir_${CMAKE_BUILD_TYPE}
                fi
            fi
            if [[ "${UNIT_TESTS}" == "false" ]]; then
                echo "Disabling unit tests"
                MTR_ARGS=\${MTR_ARGS//"--unit-tests-report"/""}
            fi
            if [[ "${CIFS_TESTS}" == "false" ]]; then
                echo "Disabling CIFS mtr"
                CI_FS_MTR=no
            else
                echo "Enabling CIFS mtr"
                CI_FS_MTR=yes
            fi

            MTR_STANDALONE_TESTS="${STANDALONE_TESTS}"
            export MTR_SUITES="${SUITES}"

            aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0
            sg docker -c "
                if [ \$(docker ps -q | wc -l) -ne 0 ]; then
                    docker ps -q | xargs docker stop --time 1 || :
                fi
                ./pxc/docker/run-test-parallel-mtr ${DOCKER_OS} ${WORKER_ID}
            "
        """
    }  // withCredentials
}

void doTestWorkerJob(Integer WORKER_ID, String SUITES, String STANDALONE_TESTS = '', boolean UNIT_TESTS = false, boolean CIFS_TESTS = false) {
    timeout(time: PIPELINE_TIMEOUT, unit: 'HOURS')  {
        script {
            echo "JENKINS_SCRIPTS_BRANCH: ${JENKINS_SCRIPTS_BRANCH}"
            echo "JENKINS_SCRIPTS_REPO: ${JENKINS_SCRIPTS_REPO}"
            sh "which git"
        }
        git branch: JENKINS_SCRIPTS_BRANCH, url: JENKINS_SCRIPTS_REPO
        script {
            prepareWorkspace()
            downloadFilesForTests()
            doTests(WORKER_ID.toString(), SUITES, STANDALONE_TESTS, UNIT_TESTS, CIFS_TESTS)
        }
        step([$class: 'JUnitResultArchiver', testResults: 'pxc/sources/pxc/results/*.xml', healthScaleFactor: 1.0])
        archiveArtifacts 'pxc/sources/pxc/results/*.xml,pxc/sources/pxc/results/pxc80-test-mtr_logs-*.tar.gz'
    }
}

void doTestWorkerJobWithGuard(Integer WORKER_ID, String SUITES, String STANDALONE_TESTS = '', boolean UNIT_TESTS = false, boolean CIFS_TESTS = false) {
    catchError(buildResult: 'UNSTABLE') {
        script {
            WORKER_ABORTED[WORKER_ID] = true
            echo "WORKER_${WORKER_ID.toString()}_ABORTED = true"
        }
        doTestWorkerJob(WORKER_ID, SUITES, STANDALONE_TESTS, UNIT_TESTS, CIFS_TESTS)
        script {
            WORKER_ABORTED[WORKER_ID] = false
            echo "WORKER_${WORKER_ID.toString()}_ABORTED = false"
        }
    } // catch
}

void checkoutSources(String COMPONENT) {
    echo "Checkout ${COMPONENT} sources"
    sh """
        # sudo is needed for better node recovery after compilation failure
        # if building failed on compilation stage directory will have files owned by docker user
        sudo git reset --hard
        sudo git clean -xdf
        sudo rm -rf sources
        ./pxc/local/checkout ${COMPONENT}
    """
}

void build(String SCRIPT) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: AWS_CREDENTIALS_ID, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """
            aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0
            sg docker -c "
                if [ \$(docker ps -q | wc -l) -ne 0 ]; then
                    docker ps -q | xargs docker stop --time 1 || :
                fi
                eval ${SCRIPT} ${DOCKER_OS}
            " 2>&1 | tee build.log
        """
    }
}

void setupTestSuitesSplit() {
    def split_script = """#!/bin/bash
        if [[ "${FULL_MTR}" == "yes" ]]; then
            # Try to get suites split from PS repo. If not present, fallback to hardcoded.
            RAW_VERSION_LINK=\$(echo \${GIT_REPO%.git} | sed -e "s:github.com:raw.githubusercontent.com:g")
            REPLY=\$(curl -Is \${RAW_VERSION_LINK}/${BRANCH}/mysql-test/suites-groups.sh | head -n 1 | awk '{print \$2}')
            CUSTOM_SPLIT=0
            if [[ \${REPLY} != 200 ]]; then
                # The given branch does not contain customized suites-groups.sh file. Use default configuration.
                echo "Using pipeline built-in MTR suites split"
                cp ./pxc/jenkins/suites-groups.sh ${WORKSPACE}/suites-groups.sh
            else
                echo "Using custom MTR suites split"
                wget \${RAW_VERSION_LINK}/${BRANCH}/mysql-test/suites-groups.sh -O ${WORKSPACE}/suites-groups.sh
                CUSTOM_SPLIT=1
            fi
            # Check if split contain all suites
            wget \${RAW_VERSION_LINK}/${BRANCH}/mysql-test/mysql-test-run.pl -O ${WORKSPACE}/mysql-test-run.pl
            chmod +x ${WORKSPACE}/suites-groups.sh
            set +e
            echo "Check if suites list is consistent with the one specified in mysql-test-run.pl"
            ${WORKSPACE}/suites-groups.sh check ${WORKSPACE}/mysql-test-run.pl ${CMAKE_BUILD_TYPE}
            CHECK_RESULT=\$?
            set -e
            echo "CHECK_RESULT: \${CHECK_RESULT}"
            # Fail only if this is built-in split.
            if [[ \${CUSTOM_SPLIT} -eq 0 ]] && [[ \${CHECK_RESULT} -ne 0 ]]; then
                echo "Default MTR split is inconsistent. Exiting."
                exit 1
            fi

            # Set suites split definition, that is WORKER_x_MTR_SUITES
            source ${WORKSPACE}/suites-groups.sh
            # Call set_suites() function if exists
            if [[ \$(type -t set_suites) == function ]]; then
                set_suites ${CMAKE_BUILD_TYPE}
            fi

            echo \${WORKER_1_MTR_SUITES} > ${WORKSPACE}/worker_1.suites
            echo \${WORKER_2_MTR_SUITES} > ${WORKSPACE}/worker_2.suites
            echo \${WORKER_3_MTR_SUITES} > ${WORKSPACE}/worker_3.suites
            echo \${WORKER_4_MTR_SUITES} > ${WORKSPACE}/worker_4.suites
            echo \${WORKER_5_MTR_SUITES} > ${WORKSPACE}/worker_5.suites
            echo \${WORKER_6_MTR_SUITES} > ${WORKSPACE}/worker_6.suites
            echo \${WORKER_7_MTR_SUITES} > ${WORKSPACE}/worker_7.suites
            echo \${WORKER_8_MTR_SUITES} > ${WORKSPACE}/worker_8.suites
        fi
    """
    def split_script_output = sh(script: split_script, returnStdout: true)
    echo split_script_output

    script {
        if (env.FULL_MTR == 'yes') {
            env.WORKER_1_MTR_SUITES = sh(returnStdout: true, script: "cat ${WORKSPACE}/worker_1.suites").trim()
            env.WORKER_2_MTR_SUITES = sh(returnStdout: true, script: "cat ${WORKSPACE}/worker_2.suites").trim()
            env.WORKER_3_MTR_SUITES = sh(returnStdout: true, script: "cat ${WORKSPACE}/worker_3.suites").trim()
            env.WORKER_4_MTR_SUITES = sh(returnStdout: true, script: "cat ${WORKSPACE}/worker_4.suites").trim()
            env.WORKER_5_MTR_SUITES = sh(returnStdout: true, script: "cat ${WORKSPACE}/worker_5.suites").trim()
            env.WORKER_6_MTR_SUITES = sh(returnStdout: true, script: "cat ${WORKSPACE}/worker_6.suites").trim()
            env.WORKER_7_MTR_SUITES = sh(returnStdout: true, script: "cat ${WORKSPACE}/worker_7.suites").trim()
            env.WORKER_8_MTR_SUITES = sh(returnStdout: true, script: "cat ${WORKSPACE}/worker_8.suites").trim()
        } else if (env.FULL_MTR == 'galera_only') {
            env.WORKER_1_MTR_SUITES = "wsrep,sys_vars,galera_encryption"
            env.WORKER_2_MTR_SUITES = "galera_nbo"
            env.WORKER_3_MTR_SUITES = "galera_3nodes"
            env.WORKER_4_MTR_SUITES = "galera_sr"
            env.WORKER_5_MTR_SUITES = "galera_3nodes_nbo"
            env.WORKER_6_MTR_SUITES = "galera_3nodes_sr"
            env.WORKER_7_MTR_SUITES = "galera|nobig"
            env.WORKER_8_MTR_SUITES = "galera|big"
        } else if (env.FULL_MTR == 'skip_mtr') {
            // It is possible that values are fetched from
            // suites-groups.sh file. Clean them.
            echo "MTR execution skip requested!"
            env.WORKER_1_MTR_SUITES = ""
            env.WORKER_2_MTR_SUITES = ""
            env.WORKER_3_MTR_SUITES = ""
            env.WORKER_4_MTR_SUITES = ""
            env.WORKER_5_MTR_SUITES = ""
            env.WORKER_6_MTR_SUITES = ""
            env.WORKER_7_MTR_SUITES = ""
            env.WORKER_8_MTR_SUITES = ""
        }

        echo "WORKER_1_MTR_SUITES: ${env.WORKER_1_MTR_SUITES}"
        echo "WORKER_2_MTR_SUITES: ${env.WORKER_2_MTR_SUITES}"
        echo "WORKER_3_MTR_SUITES: ${env.WORKER_3_MTR_SUITES}"
        echo "WORKER_4_MTR_SUITES: ${env.WORKER_4_MTR_SUITES}"
        echo "WORKER_5_MTR_SUITES: ${env.WORKER_5_MTR_SUITES}"
        echo "WORKER_6_MTR_SUITES: ${env.WORKER_6_MTR_SUITES}"
        echo "WORKER_7_MTR_SUITES: ${env.WORKER_7_MTR_SUITES}"
        echo "WORKER_8_MTR_SUITES: ${env.WORKER_8_MTR_SUITES}"
    }
}

void validatePxcBranch() {
    echo "Validating PXC branch version"
    sh """
        MY_BRANCH_BASE_MAJOR=8
        MY_BRANCH_BASE_MINOR=0

        if [ -f /usr/bin/apt ]; then
            sudo apt-get update
        fi

        if [[ ${USE_PR} == "true" ]]; then
            if [ -f /usr/bin/yum ]; then
                sudo yum -y install jq
            else
                sudo apt-get install -y jq
            fi

            GIT_REPO=\$(curl https://api.github.com/repos/percona/percona-xtradb-cluster/pulls/${BRANCH} | jq -r '.head.repo.html_url')
            BRANCH=\$(curl https://api.github.com/repos/percona/percona-xtradb-cluster/pulls/${BRANCH} | jq -r '.head.ref')
        fi

        RAW_VERSION_LINK=\$(echo \${GIT_REPO%.git} | sed -e "s:github.com:raw.githubusercontent.com:g")
        REPLY=\$(curl -Is \${RAW_VERSION_LINK}/\${BRANCH}/MYSQL_VERSION | head -n 1 | awk '{print \$2}')
        if [[ \${REPLY} != 200 ]]; then
            wget \${RAW_VERSION_LINK}/\${BRANCH}/VERSION -O ${WORKSPACE}/VERSION-${BUILD_NUMBER}
        else
            wget \${RAW_VERSION_LINK}/\${BRANCH}/MYSQL_VERSION -O ${WORKSPACE}/VERSION-${BUILD_NUMBER}
        fi
        source ${WORKSPACE}/VERSION-${BUILD_NUMBER}
        if [[ \${MYSQL_VERSION_MAJOR} -lt \${MY_BRANCH_BASE_MAJOR} ]] ; then
            echo "Are you trying to build wrong branch?"
            echo "You are trying to build \${MYSQL_VERSION_MAJOR}.\${MYSQL_VERSION_MINOR} instead of \${MY_BRANCH_BASE_MAJOR}.\${MY_BRANCH_BASE_MINOR}!"
            rm -f ${WORKSPACE}/VERSION-${BUILD_NUMBER}
            exit 1
        fi
        rm -f ${WORKSPACE}/VERSION-${BUILD_NUMBER}
    """
}

void triggerAbortedTestWorkersRerun() {
    script {
        if (env.ALLOW_ABORTED_WORKERS_RERUN == 'true') {
            echo "allow aborted reruns ${env.ALLOW_ABORTED_WORKERS_RERUN}"
            echo "WORKER_1_ABORTED: ${WORKER_ABORTED[1]}"
            echo "WORKER_2_ABORTED: ${WORKER_ABORTED[2]}"
            echo "WORKER_3_ABORTED: ${WORKER_ABORTED[3]}"
            echo "WORKER_4_ABORTED: ${WORKER_ABORTED[4]}"
            echo "WORKER_5_ABORTED: ${WORKER_ABORTED[5]}"
            echo "WORKER_6_ABORTED: ${WORKER_ABORTED[6]}"
            echo "WORKER_7_ABORTED: ${WORKER_ABORTED[7]}"
            echo "WORKER_8_ABORTED: ${WORKER_ABORTED[8]}"
            def rerunNeeded = false
            def WORKER_1_RERUN_SUITES = ""
            def WORKER_2_RERUN_SUITES = ""
            def WORKER_3_RERUN_SUITES = ""
            def WORKER_4_RERUN_SUITES = ""
            def WORKER_5_RERUN_SUITES = ""
            def WORKER_6_RERUN_SUITES = ""
            def WORKER_7_RERUN_SUITES = ""
            def WORKER_8_RERUN_SUITES = ""

            if (WORKER_ABORTED[1]) {
                echo "rerun worker 1"
                WORKER_1_RERUN_SUITES = env.WORKER_1_MTR_SUITES
                rerunNeeded = true
            } else {
                // Prevent CI_FS re-trigger
                env.CI_FS_MTR = 'no'
            }
            if (WORKER_ABORTED[2]) {
                echo "rerun worker 2"
                WORKER_2_RERUN_SUITES = env.WORKER_2_MTR_SUITES
                rerunNeeded = true
            }
            if (WORKER_ABORTED[3]) {
                echo "rerun worker 3"
                WORKER_3_RERUN_SUITES = env.WORKER_3_MTR_SUITES
                rerunNeeded = true
            }
            if (WORKER_ABORTED[4]) {
                echo "rerun worker 4"
                WORKER_4_RERUN_SUITES = env.WORKER_4_MTR_SUITES
                rerunNeeded = true
            }
            if (WORKER_ABORTED[5]) {
                echo "rerun worker 5"
                WORKER_5_RERUN_SUITES = env.WORKER_5_MTR_SUITES
                rerunNeeded = true
            }
            if (WORKER_ABORTED[6]) {
                echo "rerun worker 6"
                WORKER_6_RERUN_SUITES = env.WORKER_6_MTR_SUITES
                rerunNeeded = true
            }
            if (WORKER_ABORTED[7]) {
                echo "rerun worker 7"
                WORKER_7_RERUN_SUITES = env.WORKER_7_MTR_SUITES
                rerunNeeded = true
            }
            if (WORKER_ABORTED[8]) {
                echo "rerun worker 8"
                WORKER_8_RERUN_SUITES = env.WORKER_8_MTR_SUITES
                rerunNeeded = true
            }

            echo "rerun needed: $rerunNeeded"
            if (rerunNeeded) {
                echo "restarting aborted workers"
                build job: 'pxc-8.0-pipeline-parallel-mtr',
                wait: false,
                parameters: [
                    string(name:'BUILD_NUMBER_BINARIES', value: BUILD_NUMBER_BINARIES_FOR_RERUN),
                    string(name:'GIT_REPO', value: env.GIT_REPO),
                    string(name:'BRANCH', value: env.BRANCH),
                    string(name:'DOCKER_OS', value: env.DOCKER_OS),
                    string(name:'JOB_CMAKE', value: env.JOB_CMAKE),
                    string(name:'CMAKE_BUILD_TYPE', value: env.CMAKE_BUILD_TYPE),
                    string(name:'ANALYZER_OPTS', value: env.ANALYZER_OPTS),
                    string(name:'CMAKE_OPTS', value: env.CMAKE_OPTS),
                    string(name:'MAKE_OPTS', value: env.MAKE_OPTS),
                    string(name:'MTR_ARGS', value: env.MTR_ARGS),
                    string(name:'CI_FS_MTR', value: env.CI_FS_MTR),
                    string(name:'GALERA_PARALLEL_RUN', value: env.GALERA_PARALLEL_RUN),
                    string(name:'FULL_MTR', value:'no'),
                    string(name:'WORKER_1_MTR_SUITES', value: WORKER_1_RERUN_SUITES),
                    string(name:'WORKER_2_MTR_SUITES', value: WORKER_2_RERUN_SUITES),
                    string(name:'WORKER_3_MTR_SUITES', value: WORKER_3_RERUN_SUITES),
                    string(name:'WORKER_4_MTR_SUITES', value: WORKER_4_RERUN_SUITES),
                    string(name:'WORKER_5_MTR_SUITES', value: WORKER_5_RERUN_SUITES),
                    string(name:'WORKER_6_MTR_SUITES', value: WORKER_6_RERUN_SUITES),
                    string(name:'WORKER_7_MTR_SUITES', value: WORKER_7_RERUN_SUITES),
                    string(name:'WORKER_8_MTR_SUITES', value: WORKER_8_RERUN_SUITES),
                    string(name:'MTR_STANDALONE_TESTS', value: MTR_STANDALONE_TESTS),
                    string(name:'MTR_STANDALONE_TESTS_PARALLEL', value: MTR_STANDALONE_TESTS_PARALLEL),
                    booleanParam(name: 'ALLOW_ABORTED_WORKERS_RERUN', value: false),
                    string(name:'CUSTOM_BUILD_NAME', value: "${BUILD_TRIGGER_BY} ${env.CUSTOM_BUILD_NAME} (${BUILD_NUMBER} retry)")
                ]
            }
        }  // env.ALLOW_ABORTED_WORKERS_RERUN
    }
}

if (
    (params.ANALYZER_OPTS.contains('-DWITH_ASAN=ON')) ||
    (params.ANALYZER_OPTS.contains('-DWITH_UBSAN=ON'))
    ) { PIPELINE_TIMEOUT = 48 }

if (params.ANALYZER_OPTS.contains('-DWITH_VALGRIND=ON'))
    { PIPELINE_TIMEOUT = 144 }



pipeline {
    parameters {
        string(
            defaultValue: '',
            description: 'Reuse PXC, PXB24, PXB80 binaries built in the specified build. Useful for quick MTR test rerun without rebuild.',
            name: 'BUILD_NUMBER_BINARIES',
            trim: true)
        string(
            defaultValue: 'https://github.com/percona/percona-xtradb-cluster',
            description: 'URL to PXC repository',
            name: 'GIT_REPO',
            trim: true)
        string(
            defaultValue: '8.0',
            description: 'Tag/PR/Branch for PXC repository',
            name: 'BRANCH',
            trim: true)
        string(
            defaultValue: '',
            description: 'Custom string that will be appended to the build name visible in Jenkins',
            name: 'CUSTOM_BUILD_NAME',
            trim: true)
        booleanParam(
            defaultValue: false,
            description: 'Check only if you pass PR number to BRANCH field',
            name: 'USE_PR')
        booleanParam(
            defaultValue: true,
            description: 'If checked, the PXB80_BRANCH will be ignored and latest available version will be used',
            name: 'PXB80_LATEST')
        string(
            defaultValue: 'https://github.com/percona/percona-xtrabackup',
            description: 'URL to PXB80 repository',
            name: 'PXB80_REPO',
            trim: true)
        string(
            defaultValue: 'percona-xtrabackup-8.0.31-24',
            description: 'Tag/Branch for PXB80 repository',
            name: 'PXB80_BRANCH',
            trim: true)
        booleanParam(
            defaultValue: true,
            description: 'If checked, the PXB24_BRANCH will be ignored and latest available version will be used',
            name: 'PXB24_LATEST')
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
            choices: '/usr/bin/cmake',
            description: 'path to cmake binary',
            name: 'JOB_CMAKE')
        choice(
            choices: 'centos:7\ncentos:8\noraclelinux:9\nubuntu:focal\nubuntu:jammy\nubuntu:noble\ndebian:bullseye\ndebian:bookworm',
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
            choices: '\n-DWITH_ASAN=ON -DWITH_ASAN_SCOPE=ON\n-DWITH_ASAN=ON\n-DWITH_ASAN=ON -DWITH_ASAN_SCOPE=ON -DWITH_UBSAN=ON\n-DWITH_ASAN=ON -DWITH_UBSAN=ON\n-DWITH_UBSAN=ON\n-DWITH_MSAN=ON\n-DWITH_VALGRIND=ON',
            description: 'Enable code checking',
            name: 'ANALYZER_OPTS')
        string(
            defaultValue: '--unit-tests-report --big-test --mem',
            description: 'mysql-test-run.pl options, for options like: --big-test --only-big-test --nounit-tests --unit-tests-report',
            name: 'MTR_ARGS')
        choice(
            choices: 'yes\nno',
            description: 'Run case-insensetive MTR tests',
            name: 'CI_FS_MTR')
        string(
            defaultValue: '2',
            description: 'mtr can start n parallel server and distrbute workload among them. More parallelism is better but extra parallelism (beyond CPU power) will have less effect. This value is used for the Galera specific test suites.',
            name: 'GALERA_PARALLEL_RUN')
        choice(
            choices: 'yes\nno\ngalera_only\nskip_mtr',
            description: 'yes - full MTR\nno - run mtr suites based on variables WORKER_N_MTR_SUITES\ngalera_only - only Galera related suites (incl. wsrep and sys_var)\nskip_mtr - skip testing phase. Only build.',
            name: 'FULL_MTR')
        string(
            defaultValue: '',
            description: 'Suites to be ran on worker 1 when FULL_MTR is no. Unit tests, if requested, can be ran here only!',
            name: 'WORKER_1_MTR_SUITES')
        string(
            defaultValue: '',
            description: 'Suites to be ran on worker 2 when FULL_MTR is no',
            name: 'WORKER_2_MTR_SUITES')
        string(
            defaultValue: '',
            description: 'Suites to be ran on worker 3 when FULL_MTR is no',
            name: 'WORKER_3_MTR_SUITES')
        string(
            defaultValue: '',
            description: 'Suites to be ran on worker 4 when FULL_MTR is no',
            name: 'WORKER_4_MTR_SUITES')
        string(
            defaultValue: '',
            description: 'Suites to be ran on worker 5 when FULL_MTR is no',
            name: 'WORKER_5_MTR_SUITES')
        string(
            defaultValue: '',
            description: 'Suites to be ran on worker 6 when FULL_MTR is no',
            name: 'WORKER_6_MTR_SUITES')
        string(
            defaultValue: '',
            description: 'Suites to be ran on worker 7 when FULL_MTR is no',
            name: 'WORKER_7_MTR_SUITES')
        string(
            defaultValue: '',
            description: 'Suites to be ran on worker 8 when FULL_MTR is no',
            name: 'WORKER_8_MTR_SUITES')
        string(
            defaultValue: '',
            description: 'Space-separated test names to be executed. Worker 1 handles this request.',
            name: 'MTR_STANDALONE_TESTS')
        string(
            defaultValue: '1',
            description: 'MTR workers count for standalone tests',
            name: 'MTR_STANDALONE_TESTS_PARALLEL')
        booleanParam(
            defaultValue: true,
            description: 'Rerun aborted workers',
            name: 'ALLOW_ABORTED_WORKERS_RERUN')
    }
    agent {
        label 'micro-amazon'
    }
    options {
        skipDefaultCheckout()
        skipStagesAfterUnstable()
        timeout(time: 6, unit: 'DAYS')
        buildDiscarder(logRotator(numToKeepStr: '200', artifactNumToKeepStr: '200'))
        copyArtifactPermission('pxc-8.0-param-parallel-mtr');
    }
    stages {
        stage('Prepare') {
            steps {
                script {
                    echo "JENKINS_SCRIPTS_BRANCH: $JENKINS_SCRIPTS_BRANCH"
                    echo "JENKINS_SCRIPTS_REPO: $JENKINS_SCRIPTS_REPO"
                    echo "Using instances with LABEL ${LABEL} for build and test stages"
                }
                git branch: JENKINS_SCRIPTS_BRANCH, url: JENKINS_SCRIPTS_REPO

                script {
                    BUILD_TRIGGER_BY = " (${currentBuild.getBuildCauses()[0].userId})"
                    if (BUILD_TRIGGER_BY == " (null)") {
                        BUILD_TRIGGER_BY = " "
                    }
                    currentBuild.displayName = "${BUILD_NUMBER} ${CMAKE_BUILD_TYPE}/${DOCKER_OS}${BUILD_TRIGGER_BY} ${CUSTOM_BUILD_NAME}"
                }

                sh 'echo Prepare: \$(date -u "+%s")'

                validatePxcBranch()
                setupTestSuitesSplit()
                checkIfPxbPackagesDownloadable()

                script{
                    env.BUILD_TAG_BINARIES = "jenkins-${env.JOB_NAME}-${env.BUILD_NUMBER_BINARIES}"
                    BUILD_NUMBER_BINARIES_FOR_RERUN = env.BUILD_NUMBER_BINARIES
                    sh 'printenv'
                }
            }
        }
        stage('Check out and Build PXB/PXC') {
            when {
                beforeAgent true
                expression { env.BUILD_NUMBER_BINARIES == '' }
            }
            parallel {
                stage('Build PXC80') {
                    agent { label LABEL }
                    steps {
                        script {
	                        echo "JENKINS_SCRIPTS_BRANCH: $JENKINS_SCRIPTS_BRANCH"
	                        echo "JENKINS_SCRIPTS_REPO: $JENKINS_SCRIPTS_REPO"
                        }
                        git branch: JENKINS_SCRIPTS_BRANCH, url: JENKINS_SCRIPTS_REPO

                        checkoutSources("PXC80")
                        build("./pxc/docker/run-build-pxc-parallel-mtr")

                        script {
                            FILE_NAME = sh(
                                script: 'ls pxc/sources/pxc/results/*.tar.gz | head -1',
                                returnStdout: true
                            ).trim()
                            if (FILE_NAME != "") {
                                uploadFileToS3("$FILE_NAME", "$BUILD_TAG", "pxc80.tar.gz")
                            } else {
                                echo 'Cannot find compiled archive'
                                currentBuild.result = 'FAILURE'
                            }
                            env.BUILD_TAG_BINARIES = env.BUILD_TAG
                            BUILD_NUMBER_BINARIES_FOR_RERUN = env.BUILD_NUMBER
                        }
                    }
                }
                stage('Build PXB24') {
                    when {
                        beforeAgent true
                        expression { (env.FULL_MTR != 'skip_mtr' && PXB24_PACKAGE_TO_DOWNLOAD == '') }
                    }
                    agent { label 'docker' }
                    steps {
                        script {
	                        echo "JENKINS_SCRIPTS_BRANCH: $JENKINS_SCRIPTS_BRANCH"
	                        echo "JENKINS_SCRIPTS_REPO: $JENKINS_SCRIPTS_REPO"
       	                    sh "which git"
                        }
                        git branch: JENKINS_SCRIPTS_BRANCH, url: JENKINS_SCRIPTS_REPO

                        checkoutSources("PXB24")
                        build("./pxc/docker/run-build-pxb24")

                        script {
                            FILE_NAME = sh(
                                script: 'ls pxc/sources/pxb24/results/*.tar.gz | head -1',
                                returnStdout: true
                            ).trim()

                            if (FILE_NAME != "") {
                                uploadFileToS3("$FILE_NAME", "$BUILD_TAG", "pxb24.tar.gz")
                            } else {
                                echo 'Cannot find compiled archive'
                                currentBuild.result = 'FAILURE'
                            }
                        }
                    }
                }
                stage('Build PXB80') {
                    when {
                        beforeAgent true
                        expression { (env.FULL_MTR != 'skip_mtr' && PXB80_PACKAGE_TO_DOWNLOAD == '') }
                    }
                    agent { label LABEL }
                    steps {
                        script {
	                        echo "JENKINS_SCRIPTS_BRANCH: $JENKINS_SCRIPTS_BRANCH"
	                        echo "JENKINS_SCRIPTS_REPO: $JENKINS_SCRIPTS_REPO"
       	                    sh "which git"
                        }
                        git branch: JENKINS_SCRIPTS_BRANCH, url: JENKINS_SCRIPTS_REPO

                        checkoutSources("PXB80")
                        build("./pxc/docker/run-build-pxb80")

                        script {
                            FILE_NAME = sh(
                                script: 'ls pxc/sources/pxb80/results/*.tar.gz | head -1',
                                returnStdout: true
                            ).trim()

                            if (FILE_NAME != "") {
                                uploadFileToS3("$FILE_NAME", "$BUILD_TAG", "pxb80.tar.gz")
                            } else {
                                echo 'Cannot find compiled archive'
                                currentBuild.result = 'FAILURE'
                            }
                        }
                    }
                }
            }
        }
        stage('Test') {
            parallel {
                stage('Test - 1') {
                    when {
                        beforeAgent true
                        expression { (env.WORKER_1_MTR_SUITES?.trim() || env.MTR_STANDALONE_TESTS?.trim() || env.CI_FS_MTR?.trim() == 'yes') }
                    }
                    agent { label LABEL }
                    steps {
                        doTestWorkerJobWithGuard(1, "${WORKER_1_MTR_SUITES}", "${MTR_STANDALONE_TESTS}", true, true)
                    }
                }
                stage('Test - 2') {
                    when {
                        beforeAgent true
                        expression { (env.WORKER_2_MTR_SUITES?.trim()) }
                    }
                    agent { label LABEL }
                    steps {
                        doTestWorkerJobWithGuard(2, "${WORKER_2_MTR_SUITES}")
                    }
                }
                stage('Test - 3') {
                    when {
                        beforeAgent true
                        expression { (env.WORKER_3_MTR_SUITES?.trim()) }
                    }
                    agent { label LABEL }
                    steps {
                        doTestWorkerJobWithGuard(3, "${WORKER_3_MTR_SUITES}")
                    }
                }
                stage('Test - 4') {
                    when {
                        beforeAgent true
                        expression { (env.WORKER_4_MTR_SUITES?.trim()) }
                    }
                    agent { label LABEL }
                    steps {
                        doTestWorkerJobWithGuard(4, "${WORKER_4_MTR_SUITES}")
                    }
                }
                stage('Test - 5') {
                    when {
                        beforeAgent true
                        expression { (env.WORKER_5_MTR_SUITES?.trim()) }
                    }
                    agent { label LABEL }
                    steps {
                        doTestWorkerJobWithGuard(5, "${WORKER_5_MTR_SUITES}")
                    }
                }
                stage('Test - 6') {
                    when {
                        beforeAgent true
                        expression { (env.WORKER_6_MTR_SUITES?.trim()) }
                    }
                    agent { label LABEL }
                    steps {
                        doTestWorkerJobWithGuard(6, "${WORKER_6_MTR_SUITES}")
                    }
                }
                stage('Test - 7') {
                    when {
                        beforeAgent true
                        expression { (env.WORKER_7_MTR_SUITES?.trim()) }
                    }
                    agent { label LABEL }
                    steps {
                        doTestWorkerJobWithGuard(7, "${WORKER_7_MTR_SUITES}")
                    }
                }
                stage('Test - 8') {
                    when {
                        beforeAgent true
                        expression { (env.WORKER_8_MTR_SUITES?.trim()) }
                    }
                    agent { label LABEL }
                    steps {
                        doTestWorkerJobWithGuard(8, "${WORKER_8_MTR_SUITES}")
                    }
                }
            }
        }
    }
    post {
        always {
            triggerAbortedTestWorkersRerun()
            sh 'echo Finish: \$(date -u "+%s")'
        }
    }
}

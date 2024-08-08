PIPELINE_TIMEOUT = 10
JENKINS_SCRIPTS_BRANCH = 'innovative'
JENKINS_SCRIPTS_REPO = 'https://github.com/kamil-holubicki/jenkins-pipelines'
AWS_CREDENTIALS_ID = 'c42456e5-c28d-4962-b32c-b75d161bff27'
MAX_S3_RETRIES = 12
S3_ROOT_DIR = 's3://pxc-build-cache'
// boolean default is false, 1st item unused.
WORKER_ABORTED = new boolean[9]
BUILD_NUMBER_BINARIES_FOR_RERUN = 0
BUILD_TRIGGER_BY = ''

// We need this map to construct proper pxb tarball name
OsToGlibcMap = [
    "centos:7" : "2.17",
    "centos:8" : "2.28",
    "oraclelinux:9": "2.34",
    "ubuntu:focal" : "2.31",
    "ubuntu:jammy" : "2.35",
    "ubuntu:noble" : "2.35",
    "debian:bullseye" : "2.31",
    "debian:bookworm" : "2.35" ]

def LABEL = 'docker-32gb'

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

void downloadPackage(String PACKAGE_URL, String OUTPUT_FILE_PATH) {
    // Try do download
    echo "Downloading package ${PACKAGE_URL}"
    packageDownloadResult = sh (
    script: """
        wget --server-response ${PACKAGE_URL} -O ${OUTPUT_FILE_PATH} 2>&1 | awk '/^  HTTP/{print \$2}'
        """,
        returnStdout: true
    ).trim()

    if (packageDownloadResult != '200') {
        echo "Unable to download package ${PACKAGE_URL} result: ${packageDownloadResult}"
    } else {
        echo "Package ${PACKAGE_URL} downloaded as ${OUTPUT_FILE_PATH}"
    }
}

String getGlibcVersion() {
    if (OsToGlibcMap[env.DOCKER_OS]) {
        return OsToGlibcMap[env.DOCKER_OS]
    }
    // Fallback to something, probably will not work anyway.
    return "2.35"
}

// Because of error:
// org.jenkinsci.plugins.scriptsecurity.sandbox.RejectedAccessException:
// Scripts not permitted to use method java.lang.String compareToIgnoreCase
int compareStrings(String s1, String s2) {
    result = sh (
    script: """
            if [ "$s1" \\< "$s2" ]; then
                echo "-1"
            elif [ "$s1" \\> "$s2" ]; then
                echo "1"
            else
                echo "0"
            fi
        """,
        returnStdout: true
    ).trim()

    if (result == '1') return 1
    if (result == '-1') return -1
    return 0
}

// This function adjusts glibc (openssl) version of the package before download
void downloadPXBPackage(String PACKAGE_URL, String OUTPUT_FILE_PATH) {
    // For PXB < 8.0.35 we have to use glibc2.17 as openssl libs are bundled there
    // For PXB < 8.4.0 we have to use glibc2.17 as openssl libs are bundled there
    // For PXB >= 8.0.35 we have to use PXB with proper glibc (no openssl libs bundled)
    // For PXB >= 8.4.0 we have to use PXB with proper glibc (no openssl libs bundled)
    // https://downloads.percona.com/downloads/Percona-XtraBackup-innovative-release/Percona-XtraBackup-8.3.0-1/binary/tarball/percona-xtrabackup-8.3.0-1-Linux-x86_64.glibc2.35-minimal.tar.gz

    // Find the version requested
    String prefix = "percona-xtrabackup-"
    int startPos = PACKAGE_URL.indexOf(prefix)
    if (startPos == -1) {
        // oops!
        echo "Unable to find PXB version start position in requested URL"
        return
    }
    startPos += prefix.length()
    String versionStart = PACKAGE_URL.substring(startPos)
    echo "versionStart: ${versionStart}"

    int endPos = versionStart.indexOf("-Linux")
    if (endPos == -1) {
        // oops!
        echo "Unable to find PXB version end position in requested URL"
        return
    }

    String version = versionStart.substring(0, endPos)
    echo "Found PXB version from requested URL: ${version}"

    String glibcVersion = "2.17"
    if (compareStrings(version, "8.0.35") < 0) {
        glibcVersion = "2.17"
    } else if ( (compareStrings(version, "8.1.0") >= 0) && (compareStrings(version, "8.4.0") < 0) ) {
        glibcVersion = "2.17"
    } else {
        glibcVersion = getGlibcVersion()
    }

    echo "Using glibc version for PXB: ${glibcVersion}"

    String url = PACKAGE_URL.replaceAll("glibc[0-9]+\\.[0-9]+", "glibc" + glibcVersion)
    downloadPackage(url, OUTPUT_FILE_PATH)
}

void downloadFilesForTests() {
    sh """
        mkdir -p ./pxc/sources/pxc/results/pxb_prev_LTS
        mkdir -p ./pxc/sources/pxc/results/pxb_A
        mkdir -p ./pxc/sources/pxc/results/pxb_B
    """

    echo "PXB_PREV_LTS_VERSION_URL: ${env.PXB_PREV_LTS_VERSION_URL}"
    if (env.PXB_PREV_LTS_VERSION_URL != '') {
        downloadPXBPackage(env.PXB_PREV_LTS_VERSION_URL, "./pxc/sources/pxc/results/pxb_prev_LTS/pxb_prev_LTS.tar.gz")
    }

    echo "PXB_PREV_VERSION_URL: ${env.PXB_PREV_VERSION_URL}"
    if (env.PXB_PREV_VERSION_URL != '') {
        downloadPXBPackage(env.PXB_PREV_VERSION_URL, "./pxc/sources/pxc/results/pxb_A/pxb_A.tar.gz")
    }

    echo "PXB_THIS_VERSION_URL: ${env.PXB_THIS_VERSION_URL}"
    if (env.PXB_THIS_VERSION_URL != '') {
        downloadPXBPackage(env.PXB_THIS_VERSION_URL, "./pxc/sources/pxc/results/pxb_B/pxb_B.tar.gz")
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

void analyzeMtrLog(String logFile) {
    script {
        res = sh (
        script: """
            echo \$(cat ${logFile} | grep -c 'Not all tests completed')
            """,
            returnStdout: true
        ).trim()

        if (res != "0") {
            catchError(stageResult: 'FAILURE', buildResult: null) {
                error 'Not all tests executed.'
            }
        }

        res = sh (
        script: """
            echo \$(cat ${logFile} | grep -c 'mysql-test-run: \\*\\*\\* ERROR')
            """,
            returnStdout: true
        ).trim()

        if (res != "0") {
            catchError(stageResult: 'FAILURE', buildResult: null) {
                error 'Not all tests executed.'
            }
        }
    }
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
            analyzeMtrLog("pxc/sources/pxc/results/mtr.log")
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
    sh """
      pwd
      ls -la
    """

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
                build job: 'pxc-8.x-pipeline-parallel-mtr',
                wait: false,
                parameters: [
                    string(name:'BUILD_NUMBER_BINARIES', value: BUILD_NUMBER_BINARIES_FOR_RERUN),
                    string(name:'GIT_REPO', value: env.GIT_REPO),
                    string(name:'BRANCH', value: env.BRANCH),
                    string(name:'DOCKER_OS', value: env.DOCKER_OS),
                    string(name:'PXB_PREV_LTS_VERSION_URL', value: env.PXB_PREV_LTS_VERSION_URL),
                    string(name:'PXB_PREV_LTS_VERSION_TARGET_DIR', value: env.PXB_PREV_LTS_VERSION_TARGET_DIR),
                    string(name:'PXB_PREV_VERSION_URL', value: env.PXB_PREV_VERSION_URL),
                    string(name:'PXB_PREV_VERSION_TARGET_DIR', value: env.PXB_PREV_VERSION_TARGET_DIR),
                    string(name:'PXB_THIS_VERSION_URL', value: env.PXB_THIS_VERSION_URL),
                    string(name:'PXB_THIS_VERSION_TARGET_DIR', value: env.PXB_THIS_VERSION_TARGET_DIR),
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
            description: 'Reuse PXC, PXB binaries built in the specified build. Useful for quick MTR test rerun without rebuild.',
            name: 'BUILD_NUMBER_BINARIES',
            trim: true)
        string(
            defaultValue: 'https://github.com/percona/percona-xtradb-cluster',
            description: 'URL to PXC repository',
            name: 'GIT_REPO',
            trim: true)
        string(
            defaultValue: 'trunk',
            description: 'Tag/PR/Branch for PXC repository',
            name: 'BRANCH',
            trim: true)
        string(
            defaultValue: '',
            description: 'Custom string that will be appended to the build name visible in Jenkins',
            name: 'CUSTOM_BUILD_NAME',
            trim: true)
        string(
            defaultValue: 'https://downloads.percona.com/downloads/Percona-XtraBackup-8.0/Percona-XtraBackup-8.0.35-31/binary/tarball/percona-xtrabackup-8.0.35-31-Linux-x86_64.glibc2.17-minimal.tar.gz',
            description: 'PXB package URL pointing to previous LTS version. glibc version will be auto-adjusted by Jenkins script depending on platform. This will be stored in pxc_extra/PXB_PREV_LTS_VERSION_TARGET_DIR',
            name: 'PXB_PREV_LTS_VERSION_URL',
            trim: true)
        string(
            defaultValue: 'pxb-8.0',
            description: 'PXB package downloaded from URL will be available to PXC in pxc_extra/PXB_PREV_LTS_VERSION_TARGET_DIR',
            name: 'PXB_PREV_LTS_VERSION_TARGET_DIR',
            trim: true)
        string(
            defaultValue: 'https://downloads.percona.com/downloads/Percona-XtraBackup-innovative-release/Percona-XtraBackup-8.3.0-1/binary/tarball/percona-xtrabackup-8.3.0-1-Linux-x86_64.glibc2.17-minimal.tar.gz',
            description: 'PXB package URL pointing to previous version. glibc version will be auto-adjusted by Jenkins script depending on platform. This will be stored in pxc_extra/PXB_PREV_VERSION_TARGET_DIR',
            name: 'PXB_PREV_VERSION_URL',
            trim: true)
        string(
            defaultValue: 'pxb-8.3',
            description: 'PXB package downloaded from URL will be available to PXC in pxc_extra/PXB_PREV_VERSION_TARGET_DIR',
            name: 'PXB_PREV_VERSION_TARGET_DIR',
            trim: true)
        string(
            defaultValue: 'https://downloads.percona.com/downloads/Percona-XtraBackup-innovative-release/Percona-XtraBackup-8.3.0-1/binary/tarball/percona-xtrabackup-8.3.0-1-Linux-x86_64.glibc2.17-minimal.tar.gz',
            description: 'PXB package URL pointing to this version. glibc version will be auto-adjusted by Jenkins script depending on platform. This will be stored in pxc_extra/PXB_THIS_VERSION_TARGET_DIR',
            name: 'PXB_THIS_VERSION_URL',
            trim: true)
        string(
            defaultValue: 'pxb-8.4',
            description: 'Additional PXB package downloaded from URL will be available to PXC in pxc_extra/PXB_THIS_VERSION_TARGET_DIR',
            name: 'PXB_THIS_VERSION_TARGET_DIR',
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
            choices: 'Debug\nRelWithDebInfo',
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
        copyArtifactPermission('pxc-8.x-param-parallel-mtr');
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

                script{
                    env.BUILD_TAG_BINARIES = "jenkins-${env.JOB_NAME}-${env.BUILD_NUMBER_BINARIES}"
                    BUILD_NUMBER_BINARIES_FOR_RERUN = env.BUILD_NUMBER_BINARIES
                    sh 'printenv'
                }
            }
        }
        stage('Check out and Build PXC') {
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

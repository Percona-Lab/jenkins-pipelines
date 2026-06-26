import groovy.transform.Field

@Field int PIPELINE_TIMEOUT = 24
@Field String AWS_CREDENTIALS_ID = 'c42456e5-c28d-4962-b32c-b75d161bff27'
@Field int MAX_S3_RETRIES = 12
@Field String S3_ROOT_DIR = 's3://pxc-build-cache'
// boolean default is false, 1st item unused.
@Field boolean[] WORKER_ABORTED = new boolean[9]
@Field String BUILD_NUMBER_BINARIES_FOR_RERUN = ''
@Field String BUILD_TRIGGER_BY = ''
@Field String JOB_TO_REBUILD = ''
@Field String PXB24_PACKAGE_TO_DOWNLOAD = ''
@Field String PXB80_PACKAGE_TO_DOWNLOAD = ''
@Field String LABEL = 'docker-32gb'
@Field String MICRO_LABEL = 'micro-amazon'

// We need this map to construct proper pxb tarball name
@Field Map OsToGlibcMap = [
    "centos:7" : "2.17",
    "centos:8" : "2.28",
    "oraclelinux:9": "2.34",
    "ubuntu:focal" : "2.31",
    "ubuntu:jammy" : "2.35",
    "ubuntu:noble" : "2.35",
    "debian:bullseye" : "2.31",
    "debian:bookworm" : "2.35" ]

void checkoutScripts() {
    echo "JENKINS_SCRIPTS_REPO: ${params.JENKINS_SCRIPTS_REPO}@${params.JENKINS_SCRIPTS_BRANCH}"
    git branch: params.JENKINS_SCRIPTS_BRANCH, url: params.JENKINS_SCRIPTS_REPO
}

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
    def pxbLatestTag = sh (
    script: """
        echo \$(git -c 'versionsort.suffix=-' ls-remote --exit-code --refs --sort='v:refname' https://github.com/percona/percona-xtrabackup | grep percona-xtrabackup-${PXB_VER}.* | tail -1 | cut --delimiter='/' --fields=3)
        """,
        returnStdout: true
    ).trim()
    echo "====> PXB${PXB_VER} latest tag: ${pxbLatestTag}"

    // Try do download
    def pxbDownloadable = sh (
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
    sh """
        sudo git reset --hard
        sudo git clean -xdf
        rm -rf pxc/sources/* || :
        sudo git -C sources reset --hard || :
        sudo git -C sources clean -xdf   || :
    """
}

void doTests(String WORKER_ID, String SUITES, String STANDALONE_TESTS = '', boolean UNIT_TESTS = false, boolean CIFS_TESTS = false) {
    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: AWS_CREDENTIALS_ID, secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
        sh """#!/bin/bash
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

            aws ecr-public get-login-password --region us-east-1 | docker login --username AWS --password-stdin public.ecr.aws

            sg docker -c "
                if [ \$(docker ps -q | wc -l) -ne 0 ]; then
                    docker ps -q | xargs docker stop --time 1 || :
                fi
                ./pxc/docker/run-test-parallel-mtr ${DOCKER_OS} ${WORKER_ID} ${DOCKER_SHM_SIZE}
            "
        """
    }  // withCredentials
}

void analyzeMtrLog(String logFile) {
    script {
        def res = sh (
        script: """
            echo \$(cat ${logFile} | grep -c 'Not all tests completed')
            """,
            returnStdout: true
        ).trim()

        if (res != "0") {
            catchError(stageResult: 'FAILURE', buildResult: null) {
                error 'MTR reported "Not all tests completed".'
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
                error 'MTR runner emitted "*** ERROR".'
            }
        }
    }
}

void doTestWorkerJob(Integer WORKER_ID, String SUITES, String STANDALONE_TESTS = '', boolean UNIT_TESTS = false, boolean CIFS_TESTS = false) {
    timeout(time: PIPELINE_TIMEOUT, unit: 'HOURS')  {
        checkoutScripts()
        script {
            prepareWorkspace()
            downloadFilesForTests()
            doTests(WORKER_ID.toString(), SUITES, STANDALONE_TESTS, UNIT_TESTS, CIFS_TESTS)
            analyzeMtrLog("pxc/sources/pxc/results/mtr-test-w_${WORKER_ID}.log")
        }
        // If we do Valgrind, JUnit result files can become very large
        // and will cause Java OOM in the next step. For Valgrind we don't need
        // these results to be published, because we have custom scripts to parse
        // build log file.
        if (!params.ANALYZER_OPTS.contains('-DWITH_VALGRIND=ON')) {
            step([$class: 'JUnitResultArchiver', testResults: 'pxc/sources/pxc/results/*.xml', healthScaleFactor: 1.0])
        }
        archiveArtifacts 'pxc/sources/pxc/results/*.xml,pxc/sources/pxc/results/pxc80-test-mtr_logs-*.tar.gz,pxc/sources/pxc/results/mtr-test*.log'
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
            aws ecr-public get-login-password --region us-east-1 | docker login --username AWS --password-stdin public.ecr.aws
            sg docker -c "
                if [ \$(docker ps -q | wc -l) -ne 0 ]; then
                    docker ps -q | xargs docker stop --time 1 || :
                fi
                ${SCRIPT} ${DOCKER_OS}
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

            for i in 1 2 3 4 5 6 7 8; do
                suites_var="WORKER_\${i}_MTR_SUITES"
                echo "\${!suites_var}" > ${WORKSPACE}/worker_\${i}.suites
            done
        fi
    """
    def split_script_output = sh(script: split_script, returnStdout: true)
    echo split_script_output

    script {
        if (env.FULL_MTR == 'yes') {
            [1, 2, 3, 4, 5, 6, 7, 8].each { i ->
                env."WORKER_${i}_MTR_SUITES" = sh(returnStdout: true, script: "cat ${WORKSPACE}/worker_${i}.suites").trim()
            }
        } else if (env.FULL_MTR == 'galera_only') {
            def galeraSuites = [
                "wsrep,sys_vars,galera_encryption",
                "galera_nbo",
                "galera_3nodes",
                "galera_sr",
                "galera_3nodes_nbo",
                "galera_3nodes_sr",
                "galera|nobig",
                "galera|big",
            ]
            [1, 2, 3, 4, 5, 6, 7, 8].each { i -> env."WORKER_${i}_MTR_SUITES" = galeraSuites[i - 1] }
        } else if (env.FULL_MTR == 'skip_mtr') {
            // It is possible that values are fetched from
            // suites-groups.sh file. Clean them.
            echo "MTR execution skip requested!"
            [1, 2, 3, 4, 5, 6, 7, 8].each { i -> env."WORKER_${i}_MTR_SUITES" = "" }
        }

        [1, 2, 3, 4, 5, 6, 7, 8].each { i ->
            echo "WORKER_${i}_MTR_SUITES: ${env."WORKER_${i}_MTR_SUITES"}"
        }
    }
}

void validatePxcBranch() {
    echo "Validating PXC branch version"
    sh """#!/bin/bash
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
        if (env.ALLOW_ABORTED_WORKERS_RERUN != 'true') {
            return
        }
        echo "allow aborted reruns ${env.ALLOW_ABORTED_WORKERS_RERUN}"
        [1, 2, 3, 4, 5, 6, 7, 8].each { i -> echo "WORKER_${i}_ABORTED: ${WORKER_ABORTED[i]}" }

        def rerunSuites = [1, 2, 3, 4, 5, 6, 7, 8].collect { i ->
            if (WORKER_ABORTED[i]) {
                echo "rerun worker ${i}"
                return env."WORKER_${i}_MTR_SUITES"
            }
            return ""
        }
        // Prevent CI_FS re-trigger if worker 1 isn't being rerun
        if (!WORKER_ABORTED[1]) {
            env.CI_FS_MTR = 'no'
        }

        def rerunNeeded = rerunSuites.any { it }
        echo "rerun needed: ${rerunNeeded}"
        if (!rerunNeeded) {
            return
        }
        echo "restarting aborted workers"

        def rerunParameters = [
            string(name:'BUILD_NUMBER_BINARIES', value: BUILD_NUMBER_BINARIES_FOR_RERUN),
            string(name:'GIT_REPO', value: env.GIT_REPO),
            string(name:'BRANCH', value: env.BRANCH),
            string(name:'DOCKER_OS', value: env.DOCKER_OS),
            string(name:'JOB_CMAKE', value: env.JOB_CMAKE),
            string(name:'CMAKE_BUILD_TYPE', value: env.CMAKE_BUILD_TYPE),
            string(name:'DOCKER_SHM_SIZE', value: env.DOCKER_SHM_SIZE),
            string(name:'CLOUD', value: env.CLOUD),
            string(name:'ANALYZER_OPTS', value: env.ANALYZER_OPTS),
            string(name:'CMAKE_OPTS', value: env.CMAKE_OPTS),
            string(name:'MAKE_OPTS', value: env.MAKE_OPTS),
            string(name:'MTR_ARGS', value: env.MTR_ARGS),
            string(name:'CI_FS_MTR', value: env.CI_FS_MTR),
            string(name:'GALERA_PARALLEL_RUN', value: env.GALERA_PARALLEL_RUN),
            string(name:'FULL_MTR', value:'no'),
            string(name:'MTR_STANDALONE_TESTS', value: MTR_STANDALONE_TESTS),
            string(name:'MTR_STANDALONE_TESTS_PARALLEL', value: MTR_STANDALONE_TESTS_PARALLEL),
            booleanParam(name: 'ALLOW_ABORTED_WORKERS_RERUN', value: false),
            string(name:'CUSTOM_BUILD_NAME', value: "${BUILD_TRIGGER_BY} ${env.CUSTOM_BUILD_NAME} (${BUILD_NUMBER} retry)"),
            string(name:'JENKINS_SCRIPTS_REPO', value: params.JENKINS_SCRIPTS_REPO),
            string(name:'JENKINS_SCRIPTS_BRANCH', value: params.JENKINS_SCRIPTS_BRANCH),
        ]
        [1, 2, 3, 4, 5, 6, 7, 8].each { i ->
            rerunParameters << string(name: "WORKER_${i}_MTR_SUITES", value: rerunSuites[i - 1])
        }

        build job: JOB_TO_REBUILD,
              wait: false,
              parameters: rerunParameters
    }
}

if (
    (params.ANALYZER_OPTS.contains('-DWITH_ASAN=ON')) ||
    (params.ANALYZER_OPTS.contains('-DWITH_UBSAN=ON'))
    ) { PIPELINE_TIMEOUT = 48 }

if (params.ANALYZER_OPTS.contains('-DWITH_VALGRIND=ON'))
    { PIPELINE_TIMEOUT = 144 }

// Default: each job reruns itself. The Valgrind cloud branch below
// overrides this to target pxc-8.0-pipeline-valgrind (the only PXC job
// reachable on the as-1015cs-tnr/PS Jenkins).
JOB_TO_REBUILD = env.JOB_NAME

if (params.CLOUD == 'Hetzner') {
    LABEL = 'docker-x64'
    MICRO_LABEL = 'launcher-x64'
} else if (params.CLOUD == 'AWS') {
    LABEL = 'docker-32gb'
    MICRO_LABEL = 'micro-amazon'
} else if (params.CLOUD == 'Valgrind_docker_host') {
    LABEL = 'as-1015cs-tnr'
    MICRO_LABEL = 'launcher-x64'
    // OK, this is hack to access AWS from as-1015cs-tnr which uses PS (not PXC) jenkins
    AWS_CREDENTIALS_ID = 'c8b933cd-b8ca-41d5-b639-33fe763d3f68'
    JOB_TO_REBUILD = 'pxc-8.0-pipeline-valgrind'
} else {
    // by default fallback to AWS
    LABEL = 'docker-32gb'
    MICRO_LABEL = 'micro-amazon'
}

pipeline {
    agent { label MICRO_LABEL }

    options {
        skipDefaultCheckout()
        skipStagesAfterUnstable()
        timeout(time: 8, unit: 'DAYS')
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '20'))
        copyArtifactPermission(JOB_TO_REBUILD);
    }
    stages {
        stage('Prepare') {
            steps {
                echo "Using instances from cloud ${CLOUD} with LABEL ${LABEL} for build and test stages"
                checkoutScripts()

                script {
                    BUILD_TRIGGER_BY = " (${currentBuild.getBuildCauses()[0].userId})"
                    if (BUILD_TRIGGER_BY == " (null)") {
                        BUILD_TRIGGER_BY = " "
                    }
                    currentBuild.displayName = "${BUILD_NUMBER} ${CMAKE_BUILD_TYPE}/${DOCKER_OS}${BUILD_TRIGGER_BY} ${CUSTOM_BUILD_NAME}"
                }

                sh 'echo Prepare: $(date -u "+%s")'

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
                        checkoutScripts()

                        checkoutSources("PXC80")
                        build("./pxc/docker/run-build-pxc-parallel-mtr")

                        script {
                            def FILE_NAME = sh(
                                script: 'ls pxc/sources/pxc/results/*.tar.gz | head -1',
                                returnStdout: true
                            ).trim()
                            if (FILE_NAME == "") {
                                error 'PXC80 tarball not produced'
                            }
                            uploadFileToS3("$FILE_NAME", "$BUILD_TAG", "pxc80.tar.gz")
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
                        checkoutScripts()

                        checkoutSources("PXB24")
                        build("./pxc/docker/run-build-pxb24")

                        script {
                            def FILE_NAME = sh(
                                script: 'ls pxc/sources/pxb24/results/*.tar.gz | head -1',
                                returnStdout: true
                            ).trim()

                            if (FILE_NAME == "") {
                                error 'PXB24 tarball not produced'
                            }
                            uploadFileToS3("$FILE_NAME", "$BUILD_TAG", "pxb24.tar.gz")
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
                        checkoutScripts()

                        checkoutSources("PXB80")
                        build("./pxc/docker/run-build-pxb80")

                        script {
                            def FILE_NAME = sh(
                                script: 'ls pxc/sources/pxb80/results/*.tar.gz | head -1',
                                returnStdout: true
                            ).trim()

                            if (FILE_NAME == "") {
                                error 'PXB80 tarball not produced'
                            }
                            uploadFileToS3("$FILE_NAME", "$BUILD_TAG", "pxb80.tar.gz")
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
                        doTestWorkerJobWithGuard(1, "${WORKER_1_MTR_SUITES}", "${MTR_STANDALONE_TESTS}", true, env.CI_FS_MTR?.trim() == 'yes')
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
            sh 'echo Finish: $(date -u "+%s")'
        }
    }
}

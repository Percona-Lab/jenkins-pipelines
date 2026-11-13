library changelog: false, identifier: "lib@hetzner", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

// One job per PSMDB major version keeps each job's test history to one version, so the
// JUnit plugin finds a test's previous result in the previous build instead of walking
// the whole history. The job name suffix selects the defaults for that version.
// The quick job and the original job keep the core/unittests/dbtest defaults.
def STREAM = (env.JOB_NAME ?: '').replaceFirst(/^.*hetzner-psmdb-regression-?/, '')
def SUITES = 'https://raw.githubusercontent.com/Percona-QA/psmdb-testing/refs/heads/main/regression-tests/suites/'
def EXCLUDE_80 = '--excludeWithAnyTags=featureFlagColumnstoreIndexes,featureFlagUpdateOneWithoutShardKey,featureFlagGlobalIndexesShardingCatalog,featureFlagGlobalIndexes,featureFlagTelemetry,featureFlagAuditConfigClusterParameter,serverless,does_not_support_config_fuzzer,featureFlagDeprioritizeLowPriorityOperations,featureFlagSbeFull,featureFlagQueryStats,featureFlagTransitionToCatalogShard,requires_latch_analyzer'
def STREAM_DEFAULTS = [
    '7.0': [branch: 'v7.0', version: '7.0.45', toolchain: ['v4','v3','v5'], platform: ['scons','bazel'], executors: '18',
            testsuites: '', listsuites: SUITES + 'resmoke_psmdb_7.0.txt', integration: true, os: 'debian:12',
            resmoke: EXCLUDE_80 + ',featureFlagBulkWriteCommand'],
    '8.0': [branch: 'v8.0', version: '8.0.34', toolchain: ['v4','v3','v5'], platform: ['bazel','scons'], executors: '18',
            testsuites: '', listsuites: SUITES + 'resmoke_psmdb_8.0.txt', integration: true, os: 'debian:12',
            resmoke: EXCLUDE_80],
    '8.3': [branch: 'v8.3', version: '8.3.13', toolchain: ['v5','v4','v3'], platform: ['bazel','scons'], executors: '23',
            testsuites: '', listsuites: SUITES + 'resmoke_psmdb_8.3.txt', integration: true, os: 'ubuntu:24.04',
            resmoke: '--excludeWithAnyTags=serverless,does_not_support_config_fuzzer,requires_latch_analyzer'],
    '9.0': [branch: 'v9.0', version: '9.0.4', toolchain: ['v5','v4','v3'], platform: ['bazel','scons'], executors: '23',
            testsuites: '', listsuites: SUITES + 'resmoke_psmdb_9.0.txt',
            integration: true, os: 'ubuntu:24.04', resmoke: ''],
]
def D = STREAM_DEFAULTS.get(STREAM, [branch: 'v8.0', version: '8.0.4', toolchain: ['v4','v3','v5'], platform: ['bazel','scons'],
            executors: '1', testsuites: 'core,unittests,dbtest', listsuites: '', integration: false, os: 'ubuntu:24.04',
            resmoke: EXCLUDE_80])

pipeline {
    agent {
        label 'launcher-x64'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
    }
    parameters {
        choice(name: 'image', choices: ['build','predefined'], description: 'Build image from sources or use predefined docker image for tests')
        string(name: 'dockerfile', defaultValue: 'https://raw.githubusercontent.com/Percona-QA/psmdb-testing/refs/heads/main/regression-tests/build_image/Dockerfile_gcc_from_scratch', description: 'Dockerfile for image')
        string(name: 'branch', defaultValue: D.branch, description: 'Repo branch for build image from sources')
        string(name: 'version', defaultValue: D.version, description: 'Version for build tag (psm_ver) to build image from sources')
        string(name: 'release', defaultValue: '1', description: 'Release for build tag (psm_release) to build image from sources')
        choice(name: 'toolchain_version', choices: D.toolchain, description: 'Toolchain version: v3 for PSMDB 5.0/6.0 contains GCC 8.5.0 and Python 3.8.19; v4 for PSMDB 7.0/8.0 contains GCC 11.3.0 and Python 3.10.14; v5 for master branch (GCC 14.2.0 and Python 3.13.11)')
        choice(name: 'build_platform', choices: D.platform, description: 'Build platform bazel or scons')
        string(name: 'tag', defaultValue: D.version, description: 'Docker image tag to push/pull to/from registry, should be defined manually')
        string(name: 'parallelexecutors', defaultValue: D.executors, description: 'Number of parallel executors')
        string(name: 'testsuites', defaultValue: D.testsuites, description: 'Comma-separated list of testuites')
        string(name: 'listsuites', defaultValue: D.listsuites, description: 'URL with list of testuites')
        choice(name: 'instance', choices: ['docker-x64','docker-aarch64'], description: 'Hetzner instance type for running suites')
        string(name: 'paralleljobs', defaultValue: '4', description: 'Number of parallel jobs passed to resmoke.py')
        booleanParam(name: 'unittests',defaultValue: true, description: 'Check if list of suites contains unittests')
        booleanParam(name: 'integrationtests',defaultValue: D.integration, description: 'Check if list of suites contains integration tests')
        booleanParam(name: 'benchmarktests',defaultValue: false, description: 'Check if list of suites contains benchmark tests')
        string(name: 'OS', defaultValue: D.os, description: 'Base OS, can be changed to build the image for PBM tests')
        string(name: 'resmoke_params', defaultValue: D.resmoke, description: 'Extra params passed to resmoke.py')
    }
    options {
        withCredentials(moleculePbmJenkinsCreds())
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }
    stages {
        stage('Set build name'){
            steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.image}-${env.tag}"
                }
            }
        }
        stage ('Build image from sources') {
            agent { label "${params.instance}" }
            when {
                beforeAgent true
                environment name: 'image', value: 'build'
            }
            steps {
                git poll: false, branch: branch, url: 'https://github.com/percona/percona-server-mongodb.git'
                withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '8468e4e0-5371-4741-a9bb-7c143140acea', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                     sh """
                         sudo mkdir -p /usr/libexec/docker/cli-plugins
                         LATEST=\$(curl -s https://api.github.com/repos/docker/buildx/releases/latest | grep '"tag_name":' | sed -E 's/.*"v([^"]+)".*/\\1/')
                         if [[ ${params.instance} =~ "aarch64" ]]; then
                             sudo curl -L "https://github.com/docker/buildx/releases/download/v\${LATEST}/buildx-v\${LATEST}.linux-arm64" \\
                                            -o /usr/libexec/docker/cli-plugins/docker-buildx
                         else
                             sudo curl -L "https://github.com/docker/buildx/releases/download/v\${LATEST}/buildx-v\${LATEST}.linux-amd64" \\
                                            -o /usr/libexec/docker/cli-plugins/docker-buildx
                         fi
                         sudo chmod +x /usr/libexec/docker/cli-plugins/docker-buildx
                         sudo systemctl restart docker
                         rm -rf *
                         if [[ ${params.instance} =~ "aarch64" ]]; then
                            curl "https://awscli.amazonaws.com/awscli-exe-linux-aarch64.zip" -o "awscliv2.zip"
                         else
                            curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
                         fi
                         if [ -f "/usr/bin/yum" ] ; then sudo yum install -y unzip ; else sudo apt-get update && apt-get -y install unzip ; fi
                         unzip -o awscliv2.zip
                         sudo ./aws/install || true
                         aws ecr-public get-login-password --region us-east-1 | docker login --username AWS --password-stdin public.ecr.aws/e7j3v3n0
                         curl -o Dockerfile ${params.dockerfile}
                         VER=\$(echo ${params.version} | cut -d"." -f1)
                         build_args="--build-arg branch=${params.branch} \
                             --build-arg psm_ver=${params.version} \
                             --build-arg psm_release=${params.release} \
                             --build-arg toolchain_version=${params.toolchain_version} \
                             --build-arg build_platform=${params.build_platform} \
                             --build-arg OS=${params.OS}"
                         DOCKER_BUILDKIT=1 docker build . --target jstests -t public.ecr.aws/e7j3v3n0/psmdb-build:${params.tag} \${build_args}
                         docker push public.ecr.aws/e7j3v3n0/psmdb-build:${params.tag}
                     """
                }    
            }
            post {
                success {
                    slackNotify("#mongodb_autofeed", "#00FF00", "[${JOB_NAME}]: PSMDB compilation for branch ${params.branch} on OS ${params.OS} finished succesfully, docker image: public.ecr.aws/e7j3v3n0/psmdb-build:${params.tag}")
                }
                failure {
                    slackNotify("#mongodb_autofeed", "#FF0000", "[${JOB_NAME}]: PSMDB compilation for branch ${params.branch} on OS ${params.OS} failed - [${BUILD_URL}]")
                }
                always {
                    sh 'sudo rm -rf ./*'
                }
            }
        }
        stage ('Run suites') {
            steps {
                script {
                    def parallelexec = "${params.parallelexecutors}".toInteger()
                    def runners = [:]
                    def checklist = [].asSynchronized()
                    for (int i=0; i<parallelexec; i++) {
                        runners["${i}"] = {
                            node("${params.instance}") {
                              try{
                                stage ("node ${env.NODE_NAME}") {
                                    sh """
                                       echo -e '{\n  "experimental": true,\n  "ipv6": true,\n  "fixed-cidr-v6": "2001:db8:1::/64"\n}' | sudo tee /etc/docker/daemon.json
                                       sudo systemctl restart docker
                                    """
                                    script {
                                        def image = "public.ecr.aws/e7j3v3n0/psmdb-build:" + params.tag
                                        sh """
                                            for i in \$(seq 5); do 
                                                docker pull ${image} && break || sleep 30 
                                                echo "Docker pull failed, retrying..."
                                            done
                                        """  
                                        def suites = []
                                        if ( params.listsuites != '') {
                                            sh """
                                               curl -o suites.txt "${params.listsuites}"
                                            """
                                            suites = readFile(file: 'suites.txt').split('\n')
                                        }
                                        else {
                                            suites = "${params.testsuites}".split(',')
                                        }
                                        Collections.shuffle(Arrays.asList(suites))
                                        for (int j=0; j<suites.size(); j++) {
                                            def fullsuite = suites[j]
                                            if ( !checklist.contains(fullsuite) && !fullsuite.startsWith(" ") && !fullsuite.startsWith("unittests") && !fullsuite.startsWith("integration_tests") && !fullsuite.startsWith("benchmarks")) {
                                                checklist.add(fullsuite)
                                                def suiteArray = fullsuite.split('\\|',-1)
                                                def suite = suiteArray[0]
                                                def storage = ''
                                                def script = ''
                                                if ( suiteArray.size() >= 2 ) {
                                                    storage = suiteArray[1]
                                                } 
                                                if ( suiteArray.size() >= 3 ) {
                                                    script = suiteArray[2]
                                                }
                                                def suiteName = suite.split(' ')[0]
                                                suite += " --continueOnFailure --shuffle"
                                                if ( !suite.contains('--jobs') ) {
                                                    suite += " --jobs=${params.paralleljobs}"
                                                }
                                                if ( storage == 'wiredTiger' ) {
                                                    if ( !suite.contains('--storageEngineCacheSizeGB') ) {
                                                       suite += " --storageEngine=wiredTiger --storageEngineCacheSizeGB=1 --excludeWithAnyTags=requires_mmapv1"
                                                    }
                                                    else {
                                                       suite += " --storageEngine=wiredTiger --excludeWithAnyTags=requires_mmapv1"
                                                    }
                                                    suiteName += "-wiredTiger" 
                                                }
                                                if ( storage == 'inMemory' ) {
                                                    if ( !suite.contains('--storageEngineCacheSizeGB') ) {
                                                       suite += " --storageEngine=inMemory --storageEngineCacheSizeGB=4 --excludeWithAnyTags=requires_persistence,requires_journaling,requires_mmapv1,uses_transactions,requires_wiredtiger"
                                                    }
                                                    else {
                                                       suite += " --storageEngine=inMemory --excludeWithAnyTags=requires_persistence,requires_journaling,requires_mmapv1,uses_transactions,requires_wiredtiger"
                                                    }
                                                    suiteName +="-inMemory"
                                                }
                                                if ( script ) {
                                                    sh """ 
                                                        echo "start suite ${suiteName}"
                                                        docker run -v `pwd`/test_results:/work -w /work --rm -i ${image} bash -c 'rm -rf *'
                                                        docker run --ulimit memlock=-1 --ulimit nofile=64000:64000 -v `pwd`/test_results:/work --rm ${image} bash -c "${script} && python buildscripts/resmoke.py run --suite ${suite} ${params.resmoke_params} --reportFile=/work/resmoke_${suiteName}_s.json > /work/resmoke_${suiteName}_s.log 2>&1" || true
                                                        docker run -v `pwd`/test_results:/work -w /work --rm  ${image} bash -c 'python /opt/percona-server-mongodb/resmoke2junit.py && chmod -R 777 /work'
                                                        echo "finish suite ${suiteName}"
                                                    """
                                                }
                                                else {
                                                    sh """
                                                        echo "start suite ${suiteName}" 
                                                        docker run -v `pwd`/test_results:/work -w /work --rm -i ${image} bash -c 'rm -rf *'
                                                        docker run --ulimit memlock=-1 --ulimit nofile=64000:64000 -v `pwd`/test_results:/work --rm ${image} bash -c "python buildscripts/resmoke.py run --suite $suite ${params.resmoke_params} --reportFile=/work/resmoke_${suiteName}_s.json > /work/resmoke_${suiteName}_s.log 2>&1" || true
                                                        docker run -v `pwd`/test_results:/work -w /work --rm  ${image} bash -c 'python /opt/percona-server-mongodb/resmoke2junit.py && chmod -R 777 /work'
                                                        echo "finish suite ${suiteName}"
                                                    """
                                                }
                                                junit testResults: "test_results/junit.xml", keepLongStdio: true, allowEmptyResults: true, skipPublishingChecks: true
                                                sh """
                                                    rm -rf test_results
                                                """                                                
                                            } 
                                            else if ( !checklist.contains(fullsuite) && fullsuite.startsWith(" ") ) {
                                                checklist.add(fullsuite)
                                            }
                                        }
                                    }
                                }
                            }
                          finally {
				sh 'sudo rm -rf ./*'
				deleteDir()
			    }
                          }
                        }
                    }
                    if (params.unittests) {
                        runners["unittests"] = {
                            node("${params.instance}") {
                             try {
                                stage ("node ${env.NODE_NAME}") {
                                    withEnv(['PATH=/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/sbin:/bin']){
                                    sh """
                                        sudo mkdir -p /usr/libexec/docker/cli-plugins
                                        LATEST=\$(curl -s https://api.github.com/repos/docker/buildx/releases/latest | grep '"tag_name":' | sed -E 's/.*"v([^"]+)".*/\\1/')
                                        if [[ ${params.instance} =~ "aarch64" ]]; then
                                            sudo curl -L "https://github.com/docker/buildx/releases/download/v\${LATEST}/buildx-v\${LATEST}.linux-arm64" \\
                                                -o /usr/libexec/docker/cli-plugins/docker-buildx
                                        else
                                            sudo curl -L "https://github.com/docker/buildx/releases/download/v\${LATEST}/buildx-v\${LATEST}.linux-amd64" \\
                                                -o /usr/libexec/docker/cli-plugins/docker-buildx
                                        fi
                                        sudo chmod +x /usr/libexec/docker/cli-plugins/docker-buildx
                                        echo -e '{\n  "experimental": true,\n  "ipv6": true,\n  "fixed-cidr-v6": "2001:db8:1::/64"\n}' | sudo tee /etc/docker/daemon.json
                                        sudo systemctl restart docker
                                    """
                                    script {
                                        sh """
                                            docker pull public.ecr.aws/e7j3v3n0/psmdb-build:${params.tag} || true
                                            curl -o Dockerfile ${params.dockerfile}
                                            VER=\$(echo ${params.version} | cut -d"." -f1)
                                            build_args="--build-arg branch=${params.branch} \
                                                        --build-arg psm_ver=${params.version} \
                                                        --build-arg psm_release=${params.release} \
                                                        --build-arg toolchain_version=${params.toolchain_version} \
                                                        --build-arg build_platform=${params.build_platform} \
                                                        --build-arg OS=${params.OS}"
                                            DOCKER_BUILDKIT=1 docker build . --target unittests -t unittests \${build_args}
                                        """  
                                        def suites = []
                                        if ( params.listsuites != '') {
                                            sh """
                                               curl -o suites.txt "${params.listsuites}"
                                            """
                                            suites = readFile(file: 'suites.txt').split('\n')
                                        }
                                        else {
                                            suites = "${params.testsuites}".split(',')
                                        }
                                        Collections.shuffle(Arrays.asList(suites))
                                        for (int j=0; j<suites.size(); j++) {
                                            def fullsuite = suites[j]
                                            if ( !checklist.contains(fullsuite) && fullsuite.startsWith("unittests")) {
                                                checklist.add(fullsuite)
                                                def suiteArray = fullsuite.split('\\|',-1)
                                                def suite = suiteArray[0]
                                                def suiteName = suite.split(' ')[0]
                                                suite += " --continueOnFailure --shuffle"
                                                if ( !suite.contains('--jobs') ) {
                                                    suite += " --jobs=${params.paralleljobs}"
                                                }
                                                sh """ 
                                                    echo "start suite ${suiteName}"
                                                    docker run -v `pwd`/test_results:/work -w /work --rm -i unittests bash -c 'rm -rf *'
                                                    sudo chmod -R 777 test_results
                                                    docker run -v `pwd`/test_results:/work --rm unittests bash -c "python buildscripts/resmoke.py run --suite ${suite} --reportFile=/work/resmoke_${suiteName}_s.json > /work/resmoke_${suiteName}_s.log 2>&1" || true
                                                    docker run -v `pwd`/test_results:/work -w /work --rm unittests bash -c 'python /opt/percona-server-mongodb/resmoke2junit.py'
                                                    sudo chmod -R 777 test_results
                                                    echo "finish suite ${suiteName}"
                                                """
                                                junit testResults: "test_results/junit.xml", keepLongStdio: true, allowEmptyResults: true, skipPublishingChecks: true
                                                sh """
                                                    sudo rm -rf test_results
                                                """                                                
                                            } 
                                            else if ( !checklist.contains(fullsuite) && fullsuite.startsWith(" ") ) {
                                                checklist.add(fullsuite)
                                            }
                                        }
                                    }
                                    }
                                }
                            }
                          finally {
				sh 'sudo rm -rf ./*'
				deleteDir()
			    }
                          }
                        }
                    }
                    if (params.benchmarktests) {
                        runners["benchmarktests"] = {
                            node("${params.instance}") {
                              try {
                                stage ("node ${env.NODE_NAME}") {
                                    withEnv(['PATH=/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/sbin:/bin']){
                                    sh """
                                        sudo mkdir -p /usr/libexec/docker/cli-plugins
                                        LATEST=\$(curl -s https://api.github.com/repos/docker/buildx/releases/latest | grep '"tag_name":' | sed -E 's/.*"v([^"]+)".*/\\1/')
                                        if [[ ${params.instance} =~ "aarch64" ]]; then
                                            sudo curl -L "https://github.com/docker/buildx/releases/download/v\${LATEST}/buildx-v\${LATEST}.linux-arm64" \\
                                                -o /usr/libexec/docker/cli-plugins/docker-buildx
                                        else
                                            sudo curl -L "https://github.com/docker/buildx/releases/download/v\${LATEST}/buildx-v\${LATEST}.linux-amd64" \\
                                                -o /usr/libexec/docker/cli-plugins/docker-buildx
                                        fi
                                        sudo chmod +x /usr/libexec/docker/cli-plugins/docker-buildx
                                        echo -e '{\n  "experimental": true,\n  "ipv6": true,\n  "fixed-cidr-v6": "2001:db8:1::/64"\n}' | sudo tee /etc/docker/daemon.json
                                        sudo systemctl restart docker
                                    """
                                    script {
                                        sh """
                                            docker pull public.ecr.aws/e7j3v3n0/psmdb-build:${params.tag} || true
                                            curl -o Dockerfile ${params.dockerfile}
                                            VER=\$(echo ${params.version} | cut -d"." -f1)
                                            build_args="--build-arg branch=${params.branch} \
                                                        --build-arg psm_ver=${params.version} \
                                                        --build-arg psm_release=${params.release} \
                                                        --build-arg toolchain_version=${params.toolchain_version} \
                                                        --build-arg build_platform=${params.build_platform} \
                                                        --build-arg OS=${params.OS}"
                                            DOCKER_BUILDKIT=1 docker build . --target benchmarks -t benchmarks \${build_args}
                                        """
                                        def suites = []
                                        if ( params.listsuites != '') {
                                            sh """
                                               curl -o suites.txt "${params.listsuites}"
                                            """
                                            suites = readFile(file: 'suites.txt').split('\n')
                                        }
                                        else {
                                            suites = "${params.testsuites}".split(',')
                                        }
                                        Collections.shuffle(Arrays.asList(suites))
                                        for (int j=0; j<suites.size(); j++) {
                                            def fullsuite = suites[j]
                                            if ( !checklist.contains(fullsuite) && fullsuite.startsWith("benchmark")) {
                                                checklist.add(fullsuite)
                                                def suiteArray = fullsuite.split('\\|',-1)
                                                def suite = suiteArray[0]
                                                def suiteName = suite.split(' ')[0]
                                                suite += " --continueOnFailure --shuffle"
                                                sh """
                                                    echo "start suite ${suiteName}"
                                                    docker run -v `pwd`/test_results:/work -w /work --rm -i benchmarks bash -c 'rm -rf *'
                                                    sudo chmod -R 777 test_results
                                                    docker run -v `pwd`/test_results:/work --rm benchmarks bash -c "python buildscripts/resmoke.py run --suite ${suite} --reportFile=/work/resmoke_${suiteName}_s.json > /work/resmoke_${suiteName}_s.log 2>&1" || true
                                                    docker run -v `pwd`/test_results:/work -w /work --rm benchmarks bash -c 'python /opt/percona-server-mongodb/resmoke2junit.py'
                                                    sudo chmod -R 777 test_results
                                                    echo "finish suite ${suiteName}"
                                                """
                                                junit testResults: "test_results/junit.xml", keepLongStdio: true, allowEmptyResults: true, skipPublishingChecks: true
                                                sh """
                                                    sudo rm -rf test_results
                                                """
                                            }
                                            else if ( !checklist.contains(fullsuite) && fullsuite.startsWith(" ") ) {
                                                checklist.add(fullsuite)
                                            }
                                        }
                                    }
                                    }
                                }
                            }
                          finally {
				sh 'sudo rm -rf ./*'
				deleteDir()
			    }
                          }
                        }
                    }
                    if (params.integrationtests) {
                        runners["integration-tests"] = {
                            node("${params.instance}") {
                              try {
                                stage ("node ${env.NODE_NAME}") {
                                    withEnv(['PATH=/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/sbin:/bin']){
                                    sh """
                                        sudo mkdir -p /usr/libexec/docker/cli-plugins
                                        LATEST=\$(curl -s https://api.github.com/repos/docker/buildx/releases/latest | grep '"tag_name":' | sed -E 's/.*"v([^"]+)".*/\\1/')
                                        if [[ ${params.instance} =~ "aarch64" ]]; then
                                            sudo curl -L "https://github.com/docker/buildx/releases/download/v\${LATEST}/buildx-v\${LATEST}.linux-arm64" \\
                                                -o /usr/libexec/docker/cli-plugins/docker-buildx
                                        else
                                            sudo curl -L "https://github.com/docker/buildx/releases/download/v\${LATEST}/buildx-v\${LATEST}.linux-amd64" \\
                                                -o /usr/libexec/docker/cli-plugins/docker-buildx
                                        fi
                                        sudo chmod +x /usr/libexec/docker/cli-plugins/docker-buildx
                                        echo -e '{\n  "experimental": true,\n  "ipv6": true,\n  "fixed-cidr-v6": "2001:db8:1::/64"\n}' | sudo tee /etc/docker/daemon.json
                                        sudo systemctl restart docker
                                    """
                                    script {
                                        sh """
                                            docker pull public.ecr.aws/e7j3v3n0/psmdb-build:${params.tag} || true
                                            curl -o Dockerfile ${params.dockerfile}
                                            VER=\$(echo ${params.version} | cut -d"." -f1)
                                            build_args="--build-arg branch=${params.branch} \
                                                        --build-arg psm_ver=${params.version} \
                                                        --build-arg psm_release=${params.release} \
                                                        --build-arg toolchain_version=${params.toolchain_version} \
                                                        --build-arg build_platform=${params.build_platform} \
                                                        --build-arg OS=${params.OS}"
                                            DOCKER_BUILDKIT=1 docker build . --target integrationtests -t integrationtests \${build_args}
                                        """
                                        def suites = []
                                        if ( params.listsuites != '') {
                                            sh """
                                               curl -o suites.txt "${params.listsuites}"
                                            """
                                            suites = readFile(file: 'suites.txt').split('\n')
                                        }
                                        else {
                                            suites = "${params.testsuites}".split(',')
                                        }
                                        Collections.shuffle(Arrays.asList(suites))
                                        for (int j=0; j<suites.size(); j++) {
                                            def fullsuite = suites[j]
                                            if ( !checklist.contains(fullsuite) && fullsuite.startsWith("integration_tests")) {
                                                checklist.add(fullsuite)
                                                def suiteArray = fullsuite.split('\\|',-1)
                                                def suite = suiteArray[0]
                                                def suiteName = suite.split(' ')[0]
                                                suite += " --continueOnFailure --shuffle"
                                                if ( !suite.contains('--jobs') ) {
                                                    suite += " --jobs=${params.paralleljobs}"
                                                }
                                                    sh """ 
                                                        echo "start suite ${suiteName}"
                                                        docker run -v `pwd`/test_results:/work -w /work --rm -i integrationtests bash -c 'rm -rf *'
                                                        sudo chmod -R 777 test_results
                                                        docker run -v `pwd`/test_results:/work --rm integrationtests bash -c "python buildscripts/resmoke.py run --suite ${suite} --reportFile=/work/resmoke_${suiteName}_s.json > /work/resmoke_${suiteName}_s.log 2>&1" || true
                                                        docker run -v `pwd`/test_results:/work -w /work --rm integrationtests bash -c 'python /opt/percona-server-mongodb/resmoke2junit.py'
                                                        sudo chmod -R 777 test_results
                                                        echo "finish suite ${suiteName}"
                                                    """
                                                junit testResults: "test_results/junit.xml", keepLongStdio: true, allowEmptyResults: true, skipPublishingChecks: true
                                                sh """
                                                    sudo rm -rf test_results
                                                """                                                
                                            } 
                                            else if ( !checklist.contains(fullsuite) && fullsuite.startsWith(" ") ) {
                                                checklist.add(fullsuite)
                                            }
                                        }
                                    }
                                    }
                                }
                            }
                          finally {
				sh 'sudo rm -rf ./*'
				deleteDir()
			    }
                          }
                        }
                    }
                    parallel runners  
                }
            }
        }
    }
    post {
        success {
           slackNotify("#mongodb_autofeed", "#00FF00", "[${JOB_NAME}]: Test resuts for branch ${params.branch} on OS ${params.OS} - [${BUILD_URL}testReport/(root)/]")
        }
        unstable {
           slackNotify("#mongodb_autofeed", "#F6F930", "[${JOB_NAME}]: Test resuts for branch ${params.branch} on OS ${params.OS} - [${BUILD_URL}testReport/(root)/]")
        }
        always {
            sh '''
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}

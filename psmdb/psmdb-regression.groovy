library changelog: false, identifier: "lib@hetzner", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
    agent {
        label 'launcher-x64'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
    }
    parameters {
        choice(name: 'image', choices: ['build','predefined'], description: 'Build image from sources or use predefined docker image for tests')
        string(name: 'dockerfile', defaultValue: 'https://raw.githubusercontent.com/Percona-QA/psmdb-testing/main/regression-tests/build_image/Dockerfile_gcc_from_scratch', description: 'Dockerfile for image')
        string(name: 'branch', defaultValue: 'v8.0', description: 'Repo branch for build image from sources')
        string(name: 'version', defaultValue: '8.0.4', description: 'Version for build tag (psm_ver) to build image from sources')
        string(name: 'release', defaultValue: '1', description: 'Release for build tag (psm_release) to build image from sources')
        choice(name: 'toolchain_version', choices: ['v4','v3'], description: 'Toolchain version: v3 for PSMDB 5.0/6.0 contains GCC 8.5.0 and Python 3.8.19; v4 for PSMDB 7.0/8.0 contains GCC 11.3.0 and Python 3.10.14')
        choice(name: 'build_platform', choices: ['bazel','scons'], description: 'Build platform bazel or scons')
        booleanParam(name: 'build_pro_version',defaultValue: true, description: 'Build PSMDB pro version')
        string(name: 'tag', defaultValue: '8.0.4', description: 'Docker image tag to push/pull to/from registry, should be defined manually')
        string(name: 'parallelexecutors', defaultValue: '1', description: 'Number of parallel executors')
        string(name: 'testsuites', defaultValue: 'core,unittests,dbtest', description: 'Comma-separated list of testuites')
        string(name: 'listsuites', defaultValue: '', description: 'URL with list of testuites')
        choice(name: 'instance', choices: ['docker-x64','docker-aarch64'], description: 'Hetzner instance type for running suites')
        string(name: 'paralleljobs', defaultValue: '4', description: 'Number of parallel jobs passed to resmoke.py')
        booleanParam(name: 'unittests',defaultValue: true, description: 'Check if list of suites contains unittests')
        booleanParam(name: 'integrationtests',defaultValue: false, description: 'Check if list of suites contains integration tests')
        booleanParam(name: 'benchmarktests',defaultValue: false, description: 'Check if list of suites contains benchmark tests')
        string(name: 'OS', defaultValue: 'debian:12', description: 'Base OS, can be changed to build the image for PBM tests')
        string(name: 'resmoke_params', defaultValue: '--excludeWithAnyTags=featureFlagColumnstoreIndexes,featureFlagUpdateOneWithoutShardKey,featureFlagGlobalIndexesShardingCatalog,featureFlagGlobalIndexes,featureFlagTelemetry,featureFlagAuditConfigClusterParameter,serverless,does_not_support_config_fuzzer,featureFlagDeprioritizeLowPriorityOperations,featureFlagSbeFull,featureFlagQueryStats,featureFlagTransitionToCatalogShard,requires_latch_analyzer', description: 'Extra params passed to resmoke.py')
    }
    options {
        withCredentials(moleculePbmJenkinsCreds())
        disableConcurrentBuilds()
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
                         sudo curl -L "https://github.com/docker/buildx/releases/download/v\${LATEST}/buildx-v\${LATEST}.linux-amd64" \\
                                            -o /usr/libexec/docker/cli-plugins/docker-buildx
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

                         if [ "${params.build_pro_version}" = "true" ]; then
                             build_args="\${build_args} --build-arg pro=true"
                         fi
                         DOCKER_BUILDKIT=1 docker build . --target jstests -t public.ecr.aws/e7j3v3n0/psmdb-build:${params.tag} \${build_args}
                         docker push public.ecr.aws/e7j3v3n0/psmdb-build:${params.tag}
                     """
                }    
            }
            post {
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
                                            docker pull ${image}
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
                                                        docker run --ulimit memlock=-1 -v `pwd`/test_results:/work --rm ${image} bash -c "${script} && python buildscripts/resmoke.py run --suite ${suite} ${params.resmoke_params} --reportFile=/work/resmoke_${suiteName}_s.json > /work/resmoke_${suiteName}_s.log 2>&1" || true
                                                        docker run -v `pwd`/test_results:/work -w /work --rm  ${image} bash -c 'python /opt/percona-server-mongodb/resmoke2junit.py && chmod -R 777 /work'
                                                        echo "finish suite ${suiteName}"
                                                    """
                                                }
                                                else {
                                                    sh """
                                                        echo "start suite ${suiteName}" 
                                                        docker run -v `pwd`/test_results:/work -w /work --rm -i ${image} bash -c 'rm -rf *'
                                                        docker run --ulimit memlock=-1 -v `pwd`/test_results:/work --rm ${image} bash -c "python buildscripts/resmoke.py run --suite $suite ${params.resmoke_params} --reportFile=/work/resmoke_${suiteName}_s.json > /work/resmoke_${suiteName}_s.log 2>&1" || true
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
                                        sudo curl -L "https://github.com/docker/buildx/releases/download/v\${LATEST}/buildx-v\${LATEST}.linux-amd64" \\
                                            -o /usr/libexec/docker/cli-plugins/docker-buildx
                                        sudo chmod +x /usr/libexec/docker/cli-plugins/docker-buildx
                                        echo -e '{\n  "experimental": true,\n  "ipv6": true,\n  "fixed-cidr-v6": "2001:db8:1::/64"\n}' | sudo tee /etc/docker/daemon.json
                                        sudo systemctl restart docker
                                    """
                                    script {
                                        sh """
                                            docker pull public.ecr.aws/e7j3v3n0/psmdb-build:${params.tag}
                                            curl -o Dockerfile ${params.dockerfile}
                                            VER=\$(echo ${params.version} | cut -d"." -f1)
                                            build_args="--build-arg branch=${params.branch} \
                                                        --build-arg psm_ver=${params.version} \
                                                        --build-arg psm_release=${params.release} \
                                                        --build-arg toolchain_version=${params.toolchain_version} \
                                                        --build-arg build_platform=${params.build_platform} \
                                                        --build-arg OS=${params.OS}"

                                            if [ "${params.build_pro_version}" = "true" ]; then
                                                 build_args="\${build_args} --build-arg pro=true"
                                            fi
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
                                        sudo curl -L "https://github.com/docker/buildx/releases/download/v\${LATEST}/buildx-v\${LATEST}.linux-amd64" \\
                                            -o /usr/libexec/docker/cli-plugins/docker-buildx
                                        sudo chmod +x /usr/libexec/docker/cli-plugins/docker-buildx
                                        echo -e '{\n  "experimental": true,\n  "ipv6": true,\n  "fixed-cidr-v6": "2001:db8:1::/64"\n}' | sudo tee /etc/docker/daemon.json
                                        sudo systemctl restart docker
                                    """
                                    script {
                                        sh """
                                            docker pull public.ecr.aws/e7j3v3n0/psmdb-build:${params.tag}
                                            curl -o Dockerfile ${params.dockerfile}
                                            VER=\$(echo ${params.version} | cut -d"." -f1)
                                            build_args="--build-arg branch=${params.branch} \
                                                        --build-arg psm_ver=${params.version} \
                                                        --build-arg psm_release=${params.release} \
                                                        --build-arg toolchain_version=${params.toolchain_version} \
                                                        --build-arg build_platform=${params.build_platform} \
                                                        --build-arg OS=${params.OS}"

                                            if [ "${params.build_pro_version}" = "true" ]; then
                                                 build_args="\${build_args} --build-arg pro=true"
                                            fi
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
                                                if ( !suite.contains('--jobs') ) {
                                                    suite += " --jobs=${params.paralleljobs}"
                                                }
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
                                        sudo curl -L "https://github.com/docker/buildx/releases/download/v\${LATEST}/buildx-v\${LATEST}.linux-amd64" \\
                                            -o /usr/libexec/docker/cli-plugins/docker-buildx
                                        sudo chmod +x /usr/libexec/docker/cli-plugins/docker-buildx
                                        echo -e '{\n  "experimental": true,\n  "ipv6": true,\n  "fixed-cidr-v6": "2001:db8:1::/64"\n}' | sudo tee /etc/docker/daemon.json
                                        sudo systemctl restart docker
                                    """
                                    script {
                                        sh """
                                            docker pull public.ecr.aws/e7j3v3n0/psmdb-build:${params.tag}
                                            curl -o Dockerfile ${params.dockerfile}
                                            VER=\$(echo ${params.version} | cut -d"." -f1)
                                            build_args="--build-arg branch=${params.branch} \
                                                        --build-arg psm_ver=${params.version} \
                                                        --build-arg psm_release=${params.release} \
                                                        --build-arg toolchain_version=${params.toolchain_version} \
                                                        --build-arg build_platform=${params.build_platform} \
                                                        --build-arg OS=${params.OS}"

                                            if [ "${params.build_pro_version}" = "true" ]; then
                                                 build_args="\${build_args} --build-arg pro=true"
                                            fi
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
       always {
            sh '''
                sudo rm -rf ./*
            '''
            deleteDir()
       }
    }
}

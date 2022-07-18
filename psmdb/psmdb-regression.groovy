library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

pipeline {
    agent {
        label 'docker-32gb'
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
    }
    parameters {
        choice(name: 'image', choices: ['build','tarball','predefined'], description: 'Build image from sources, build image from tarball, or use predefined docker image for tests')
        string(name: 'branch', defaultValue: 'release-4.4.9-10', description: 'Repo branch for build image from sources')
        string(name: 'version', defaultValue: '4.4.9', description: 'Version for build tag (psm_ver) to build image from sources')
        string(name: 'release', defaultValue: '10', description: 'Release for build tag (psm_release) to build image from sources')
        string(name: 'mongo_tools', defaultValue: '100.4.1', description: 'Mongo tools tag (mongo_tools_tag) to build image from sources')
        string(name: 'srctarball', defaultValue: 'https://downloads.percona.com/downloads/percona-server-mongodb-LATEST/percona-server-mongodb-4.4.9-10/source/tarball/percona-server-mongodb-4.4.9-10.tar.gz', description: 'Tarball with sources to build image from ready tarballs')
        string(name: 'bintarball', defaultValue: 'https://downloads.percona.com/downloads/percona-server-mongodb-LATEST/percona-server-mongodb-4.4.9-10/binary/tarball/percona-server-mongodb-4.4.9-10-x86_64.glibc2.17.tar.gz', description: 'Tarball with binaries to build image from ready tarballs')
        string(name: 'tag', defaultValue: '4.4.9', description: 'Docker image tag to push/pull to/from registry, should be defined manually')
        string(name: 'parallelexecutors', defaultValue: '1', description: 'Number of parallel executors')
        string(name: 'testsuites', defaultValue: 'core', description: 'Comma-separated list of testuites')
        string(name: 'listsuites', defaultValue: '', description: 'URL with list of testuites')
        choice(name: 'instance', choices: ['docker','docker-32gb'], description: 'Ec2 instance type for running suites')
        string(name: 'paralleljobs', defaultValue: '2', description: 'Number of parallel jobs passed to resmoke.py')
        booleanParam(name: 'unittests',defaultValue: false, description: 'Check if list of suites contains unittests')
        booleanParam(name: 'integrationtests',defaultValue: false, description: 'Check if list of suites contains integration tests')
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
            agent { label 'docker-32gb' }
            when {
                beforeAgent true
                environment name: 'image', value: 'build'
            }
            steps {
                git poll: false, branch: branch, url: 'https://github.com/percona/percona-server-mongodb.git'
                withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '8468e4e0-5371-4741-a9bb-7c143140acea', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                     sh """
                         rm -rf *
                         curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
                         if [ -f "/usr/bin/yum" ] ; then sudo yum install -y unzip ; else sudo apt-get update && apt-get -y install unzip ; fi
                         unzip -o awscliv2.zip
                         sudo ./aws/install
                         aws ecr-public get-login-password --region us-east-1 | docker login --username AWS --password-stdin public.ecr.aws/e7j3v3n0
                         curl -o Dockerfile https://raw.githubusercontent.com/Percona-QA/psmdb-testing/main/regression-tests/build_image/Dockerfile
                         docker build . -t public.ecr.aws/e7j3v3n0/psmdb-build:${params.tag} \
                                --build-arg branch=${params.branch} \
                                --build-arg psm_ver=${params.version} \
                                --build-arg psm_release=${params.release} \
                                --build-arg mongo_tools_tag=${params.mongo_tools}
                         docker push public.ecr.aws/e7j3v3n0/psmdb-build:${params.tag}      
                     """
                }    
            }
        }
        stage ('Build image from tarball') {
            agent { label 'docker-32gb' }
            when {
                beforeAgent true
                environment name: 'image', value: 'tarball'
            }
            steps {
                withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: '8468e4e0-5371-4741-a9bb-7c143140acea', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                     sh """
                         curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
                         if [ -f "/usr/bin/yum" ] ; then sudo yum install -y unzip ; else sudo apt-get update && apt-get -y install unzip ; fi
                         unzip -o awscliv2.zip
                         sudo ./aws/install
                         aws ecr-public get-login-password --region us-east-1 | docker login --username AWS --password-stdin public.ecr.aws/e7j3v3n0
                         curl -o Dockerfile https://raw.githubusercontent.com/Percona-QA/psmdb-testing/main/regression-tests/tarball_image/Dockerfile
                         docker build . -t public.ecr.aws/e7j3v3n0/psmdb-build:${params.tag} \
                                --build-arg branch=${params.branch} \
                                --build-arg sources=${params.srctarball} \
                                --build-arg tarball=${params.bintarball} 
                         docker push public.ecr.aws/e7j3v3n0/psmdb-build:${params.tag}
                     """
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
                                            if ( !checklist.contains(fullsuite) && !fullsuite.startsWith(" ") && !fullsuite.startsWith("unittests") && !fullsuite.startsWith("integration_tests")) {
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
                                                    suite += " --storageEngine=wiredTiger --storageEngineCacheSizeGB=1 --excludeWithAnyTags=requires_mmapv1"
                                                    suiteName += "-wiredTiger" 
                                                }
                                                if ( storage == 'inMemory' ) {
                                                    suite += " --storageEngine=inMemory --storageEngineCacheSizeGB=4 --excludeWithAnyTags=requires_persistence,requires_journaling,requires_mmapv1,uses_transactions"
                                                    suiteName +="-inMemory"
                                                }
                                                if ( script ) {
                                                    sh """ 
                                                        echo "start suite ${suiteName}"
                                                        docker run -v `pwd`/test_results:/work -w /work --rm -i ${image} bash -c 'rm -rf *'
                                                        docker run -v `pwd`/test_results:/work --rm ${image} bash -c "${script} && python buildscripts/resmoke.py run --suite ${suite} --reportFile=/work/resmoke_${suiteName}_s.json > /work/resmoke_${suiteName}_s.log 2>&1" || true
                                                        docker run -v `pwd`/test_results:/work -w /work --rm  ${image} bash -c 'python /opt/percona-server-mongodb/resmoke2junit.py && chmod -R 777 /work'
                                                        echo "finish suite ${suiteName}"
                                                    """
                                                }
                                                else {
                                                    sh """
                                                        echo "start suite ${suiteName}" 
                                                        docker run -v `pwd`/test_results:/work -w /work --rm -i ${image} bash -c 'rm -rf *'
                                                        docker run -v `pwd`/test_results:/work --rm ${image} bash -c "python buildscripts/resmoke.py run --suite $suite --reportFile=/work/resmoke_${suiteName}_s.json > /work/resmoke_${suiteName}_s.log 2>&1" || true
                                                        docker run -v `pwd`/test_results:/work -w /work --rm  ${image} bash -c 'python /opt/percona-server-mongodb/resmoke2junit.py && chmod -R 777 /work'
                                                        echo "finish suite ${suiteName}"
                                                    """
                                                }
                                                junit testResults: "test_results/junit.xml", keepLongStdio: true
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
                        }
                    }
                    if (params.unittests) {
                        runners["unittests"] = {
                            node("psmdb-bionic") {
                                stage ("node ${env.NODE_NAME}") {
                                    withEnv(['PATH=/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/sbin:/bin']){
                                    sh '''
                                        curl https://get.docker.com -o get-docker.sh
                                        sudo sh get-docker.sh
                                        sudo install -o root -g root -d /mnt/docker
                                        sudo usermod -aG docker $(id -u -n)
                                        sudo mkdir -p /etc/docker
                                        echo -e '{\n  "experimental": true,\n  "ipv6": true,\n  "fixed-cidr-v6": "2001:db8:1::/64",\n  "data-root": "/mnt/docker"\n}' | sudo tee /etc/docker/daemon.json
                                        sudo systemctl restart docker
                                        sudo systemctl status docker || sudo systemctl start docker
                                        sudo setfacl --modify user:$USER:rw /var/run/docker.sock
                                    '''
                                    script {
                                        def image = "public.ecr.aws/e7j3v3n0/psmdb-build:" + params.tag
                                        sh """
                                            docker pull ${image}
                                            docker run -v `pwd`/build:/opt/percona-server-mongodb/build -i --rm ${image} bash -c 'buildscripts/scons.py CC=/usr/bin/gcc-8 CXX=/usr/bin/g++-8 --disable-warnings-as-errors --release --ssl --opt=size -j6 --use-sasl-client --wiredtiger --audit --inmemory --hotbackup CPPPATH=/usr/local/include LIBPATH="/usr/local/lib /usr/local/lib64" install-unittests'
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
                                                    suite += " --storageEngine=wiredTiger --storageEngineCacheSizeGB=1 --excludeWithAnyTags=requires_mmapv1"
                                                    suiteName += "-wiredTiger" 
                                                }
                                                if ( storage == 'inMemory' ) {
                                                    suite += " --storageEngine=inMemory --storageEngineCacheSizeGB=4 --excludeWithAnyTags=requires_persistence,requires_journaling,requires_mmapv1,uses_transactions"
                                                    suiteName +="-inMemory"
                                                }
                                                if ( script ) {
                                                    sh """ 
                                                        echo "start suite ${suiteName}"
                                                        docker run -v `pwd`/test_results:/work -v `pwd`/build:/opt/percona-server-mongodb/build -w /work --rm -i ${image} bash -c 'rm -rf *'
                                                        docker run -v `pwd`/test_results:/work -v `pwd`/build:/opt/percona-server-mongodb/build --rm ${image} bash -c "${script} && python buildscripts/resmoke.py run --suite ${suite} --reportFile=/work/resmoke_${suiteName}_s.json > /work/resmoke_${suiteName}_s.log 2>&1" || true
                                                        docker run -v `pwd`/test_results:/work -v `pwd`/build:/opt/percona-server-mongodb/build -w /work --rm  ${image} bash -c 'python /opt/percona-server-mongodb/resmoke2junit.py && chmod -R 777 /work'
                                                        echo "finish suite ${suiteName}"
                                                    """
                                                }
                                                else {
                                                    sh """
                                                        echo "start suite ${suiteName}" 
                                                        docker run -v `pwd`/test_results:/work -v `pwd`/build:/opt/percona-server-mongodb/build -w /work --rm -i ${image} bash -c 'rm -rf *'
                                                        docker run -v `pwd`/test_results:/work -v `pwd`/build:/opt/percona-server-mongodb/build --rm ${image} bash -c "python buildscripts/resmoke.py run --suite $suite --reportFile=/work/resmoke_${suiteName}_s.json > /work/resmoke_${suiteName}_s.log 2>&1" || true
                                                        docker run -v `pwd`/test_results:/work -v `pwd`/build:/opt/percona-server-mongodb/build -w /work --rm  ${image} bash -c 'python /opt/percona-server-mongodb/resmoke2junit.py && chmod -R 777 /work'
                                                        echo "finish suite ${suiteName}"
                                                    """
                                                }
                                                junit testResults: "test_results/junit.xml", keepLongStdio: true
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
                            }
                        }
                    }
                    if (params.integrationtests) {
                        runners["integration-tests"] = {
                            node("psmdb-bionic") {
                                stage ("node ${env.NODE_NAME}") {
                                    withEnv(['PATH=/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/sbin:/bin']){
                                    sh '''
                                        curl https://get.docker.com -o get-docker.sh
                                        sudo sh get-docker.sh
                                        sudo install -o root -g root -d /mnt/docker
                                        sudo usermod -aG docker $(id -u -n)
                                        sudo mkdir -p /etc/docker
                                        echo -e '{\n  "experimental": true,\n  "ipv6": true,\n  "fixed-cidr-v6": "2001:db8:1::/64",\n  "data-root": "/mnt/docker"\n}' | sudo tee /etc/docker/daemon.json
                                        sudo systemctl restart docker
                                        sudo systemctl status docker || sudo systemctl start docker
                                        sudo setfacl --modify user:$USER:rw /var/run/docker.sock
                                    '''
                                    script {
                                        def image = "public.ecr.aws/e7j3v3n0/psmdb-build:" + params.tag
                                        sh """
                                            docker pull ${image}
                                            docker run -v `pwd`/build:/opt/percona-server-mongodb/build -i --rm ${image} bash -c 'buildscripts/scons.py CC=/usr/bin/gcc-8 CXX=/usr/bin/g++-8 --disable-warnings-as-errors --release --ssl --opt=size -j6 --use-sasl-client --wiredtiger --audit --inmemory --hotbackup CPPPATH=/usr/local/include LIBPATH="/usr/local/lib /usr/local/lib64" install-integration-tests'
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
                                                    suite += " --storageEngine=wiredTiger --storageEngineCacheSizeGB=1 --excludeWithAnyTags=requires_mmapv1"
                                                    suiteName += "-wiredTiger" 
                                                }
                                                if ( storage == 'inMemory' ) {
                                                    suite += " --storageEngine=inMemory --storageEngineCacheSizeGB=4 --excludeWithAnyTags=requires_persistence,requires_journaling,requires_mmapv1,uses_transactions"
                                                    suiteName +="-inMemory"
                                                }
                                                if ( script ) {
                                                    sh """ 
                                                        echo "start suite ${suiteName}"
                                                        docker run -v `pwd`/test_results:/work -v `pwd`/build:/opt/percona-server-mongodb/build -w /work --rm -i ${image} bash -c 'rm -rf *'
                                                        docker run -v `pwd`/test_results:/work -v `pwd`/build:/opt/percona-server-mongodb/build --rm ${image} bash -c "${script} && python buildscripts/resmoke.py run --suite ${suite} --reportFile=/work/resmoke_${suiteName}_s.json > /work/resmoke_${suiteName}_s.log 2>&1" || true
                                                        docker run -v `pwd`/test_results:/work -v `pwd`/build:/opt/percona-server-mongodb/build -w /work --rm  ${image} bash -c 'python /opt/percona-server-mongodb/resmoke2junit.py && chmod -R 777 /work'
                                                        echo "finish suite ${suiteName}"
                                                    """
                                                }
                                                else {
                                                    sh """
                                                        echo "start suite ${suiteName}" 
                                                        docker run -v `pwd`/test_results:/work -v `pwd`/build:/opt/percona-server-mongodb/build -w /work --rm -i ${image} bash -c 'rm -rf *'
                                                        docker run -v `pwd`/test_results:/work -v `pwd`/build:/opt/percona-server-mongodb/build --rm ${image} bash -c "python buildscripts/resmoke.py run --suite $suite --reportFile=/work/resmoke_${suiteName}_s.json > /work/resmoke_${suiteName}_s.log 2>&1" || true
                                                        docker run -v `pwd`/test_results:/work -v `pwd`/build:/opt/percona-server-mongodb/build -w /work --rm  ${image} bash -c 'python /opt/percona-server-mongodb/resmoke2junit.py && chmod -R 777 /work'
                                                        echo "finish suite ${suiteName}"
                                                    """
                                                }
                                                junit testResults: "test_results/junit.xml", keepLongStdio: true
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

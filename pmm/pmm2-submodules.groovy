library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runAPItests(String DOCKER_IMAGE_VERSION, BRANCH_NAME, GIT_COMMIT_HASH, CLIENT_VERSION, OWNER) {
    apiTestJob = build job: 'pmm2-api-tests', propagate: false, parameters: [
        string(name: 'DOCKER_VERSION', value: DOCKER_IMAGE_VERSION),
        string(name: 'GIT_BRANCH', value: BRANCH_NAME),
        string(name: 'OWNER', value: OWNER),
        string(name: 'GIT_COMMIT_HASH', value: GIT_COMMIT_HASH)
    ]
    env.API_TESTS_URL = apiTestJob.absoluteUrl
    env.API_TESTS_RESULT = apiTestJob.result
}

void runTestSuite(String DOCKER_IMAGE_VERSION, CLIENT_VERSION, PMM_QA_GIT_BRANCH, PMM_QA_GIT_COMMIT_HASH, PMM_VERSION) {
    testSuiteJob = build job: 'pmm2-testsuite', propagate: false, parameters: [
        string(name: 'DOCKER_VERSION', value: DOCKER_IMAGE_VERSION),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'PMM_QA_GIT_BRANCH', value: PMM_QA_GIT_BRANCH),
        string(name: 'PMM_QA_GIT_COMMIT_HASH', value: PMM_QA_GIT_COMMIT_HASH),
        string(name: 'PMM_VERSION', value: PMM_VERSION)
    ]
    env.BATS_TESTS_URL = testSuiteJob.absoluteUrl
    env.BATS_TESTS_RESULT = testSuiteJob.result
}

void runUItests(String DOCKER_IMAGE_VERSION, CLIENT_VERSION, PMM_QA_GIT_BRANCH, PMM_QA_GIT_COMMIT_HASH) {
    e2eTestJob = build job: 'pmm2-ui-tests', propagate: false, parameters: [
        string(name: 'DOCKER_VERSION', value: DOCKER_IMAGE_VERSION),
        string(name: 'CLIENT_VERSION', value: CLIENT_VERSION),
        string(name: 'GIT_BRANCH', value: PMM_QA_GIT_BRANCH),
        string(name: 'GIT_COMMIT_HASH', value: PMM_QA_GIT_COMMIT_HASH)
    ]
    env.UI_TESTS_URL = e2eTestJob.absoluteUrl
    env.UI_TESTS_RESULT = e2eTestJob.result
}

void addComment(String COMMENT) {
    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
        sh """
            curl -v -X POST \
                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                -d "{\\"body\\":\\"${COMMENT}\\"}" \
                "https://api.github.com/repos/\$(echo $CHANGE_URL | cut -d '/' -f 4-5)/issues/${CHANGE_ID}/comments"
        """
    }
}

pipeline {
    agent {
        label 'large-amazon'
    }
    stages {
        stage('Prepare') {
            steps {
                withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
                sh '''
                    set -o errexit
                    if [ -s ci.yml ]
                    then
                        sudo rm -rf results tmp || :
                        git reset --hard
                        git clean -fdx
                        sudo yum install -y python3
                        sudo pip3 install -r requirements.txt
                        python3 ci.py
                        . ./.git-sources
                        curl -s https://api.github.com/repos/percona/pmm-managed/commits/${pmm_managed_commit} | grep 'name' | awk -F '"' '{print $4}' | head -1 > OWNER
                        echo $pmm_managed_commit > apiCommitSha
                        echo $pmm_managed_branch > apiBranch
                        echo $pmm_qa_branch > pmmQABranch
                        echo $pmm_qa_commit > pmmQACommitSha
                        echo $pmm_ui_tests_branch > pmmUITestBranch
                        echo $pmm_ui_tests_commit > pmmUITestsCommitSha
                    else
                        curdir=$(pwd)
                        cd ../
                        wget https://github.com/git-lfs/git-lfs/releases/download/v2.7.1/git-lfs-linux-amd64-v2.7.1.tar.gz
                        tar -zxvf git-lfs-linux-amd64-v2.7.1.tar.gz
                        sudo ./install.sh
                        cd $curdir
                        sudo rm -rf results tmp || :
                        git reset --hard
                        git clean -fdx
                        git submodule foreach --recursive git reset --hard
                        git submodule foreach --recursive git clean -fdx
                        git submodule status
                        export commit_sha=$(git submodule status | grep 'pmm-managed' | awk -F ' ' '{print $1}')
                        curl -s https://api.github.com/repos/percona/pmm-managed/commits/${commit_sha} | grep 'name' | awk -F '"' '{print $4}' | head -1 > OWNER
                        cd sources/pmm-server/
                        git lfs install
                        git lfs pull
                        git lfs checkout
                        cd $curdir
                        export api_tests_commit_sha=$(git submodule status | grep 'pmm-managed' | awk -F ' ' '{print $1}')
                        export api_tests_branch=$(git config -f .gitmodules submodule.pmm-managed.branch)
                        echo $api_tests_commit_sha > apiCommitSha
                        echo $api_tests_branch > apiBranch
                        cat apiBranch
                        export pmm_qa_commit_sha=$(git submodule status | grep 'pmm-qa' | awk -F ' ' '{print $1}')
                        export pmm_qa_branch=$(git config -f .gitmodules submodule.pmm-qa.branch)
                        echo $pmm_qa_branch > pmmQABranch
                        echo $pmm_qa_commit_sha > pmmQACommitSha
                        export pmm_ui_tests_commit_sha=$(git submodule status | grep 'pmm-ui-tests' | awk -F ' ' '{print $1}')
                        export pmm_ui_tests_branch=$(git config -f .gitmodules submodule.pmm-ui-tests.branch)
                        echo $pmm_ui_tests_branch > pmmUITestBranch
                        echo $pmm_ui_tests_commit_sha > pmmUITestsCommitSha
                        cd $curdir
                    fi
                '''
                }
                installDocker()
                script {
                    env.PMM_VERSION = sh(returnStdout: true, script: "cat VERSION").trim()
                }
                stash includes: 'apiBranch', name: 'apiBranch'
                stash includes: 'pmmQABranch', name: 'pmmQABranch'
                stash includes: 'apiCommitSha', name: 'apiCommitSha'
                stash includes: 'pmmQACommitSha', name: 'pmmQACommitSha'
                stash includes: 'pmmUITestBranch', name: 'pmmUITestBranch'
                stash includes: 'pmmUITestsCommitSha', name: 'pmmUITestsCommitSha'
                slackSend channel: '#pmm-ci', color: '#FFFF00', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
            }
        }
        stage('Build client source') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        sg docker -c "
                            set -o errexit

                            aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                            env
                            ./build/bin/build-client-source
                        "
                    '''
                }
            }
        }
        stage('Build client binary') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        sg docker -c "
                            set -o errexit

                            aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                            env
                            ./build/bin/build-client-binary
                        "
                        aws s3 cp \
                            --acl public-read \
                            results/tarball/pmm2-client-*.tar.gz \
                            s3://pmm-build-cache/PR-BUILDS/pmm2-client/pmm2-client-${BRANCH_NAME}-${GIT_COMMIT:0:7}.tar.gz
                        '''
                }
                script {
                    def clientPackageURL = sh script:'echo "https://s3.us-east-2.amazonaws.com/pmm-build-cache/PR-BUILDS/pmm2-client/pmm2-client-${BRANCH_NAME}-${GIT_COMMIT:0:7}.tar.gz" | tee CLIENT_URL', returnStdout: true
                    env.CLIENT_URL = sh(returnStdout: true, script: "cat CLIENT_URL").trim()
                }
                stash includes: 'CLIENT_URL', name: 'CLIENT_URL'
            }
        }
        stage('Build client source rpm') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        sg docker -c "
                            set -o errexit
                            aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                            ./build/bin/build-client-srpm centos:7
                        "
                    '''
                }
            }
        }
        stage('Build client binary rpm') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        sg docker -c "
                            set -o errexit

                            aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                            ./build/bin/build-client-rpm centos:7

                            mkdir -p tmp/pmm-server/RPMS/
                            cp results/rpm/pmm2-client-*.rpm tmp/pmm-server/RPMS/
                        "
                    '''
                }
            }
        }
        stage('Build client docker') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        sg docker -c "
                            docker login -u "${USER}" -p "${PASS}"
                        "
                    """
                }
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        sg docker -c "
                            set -o errexit
                            aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                            export PUSH_DOCKER=1
                            export DOCKER_CLIENT_TAG=perconalab/pmm-client-fb:${BRANCH_NAME}-${GIT_COMMIT:0:7}

                            ./build/bin/build-client-docker
                        "
                    '''
                }
                stash includes: 'results/docker/CLIENT_TAG', name: 'CLIENT_IMAGE'
                archiveArtifacts 'results/docker/CLIENT_TAG'
            }
        }
        stage('Build server packages') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        sg docker -c "
                            set -o errexit

                            aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                            export RPM_EPOCH=1
                            export PATH=$PATH:$(pwd -P)/build/bin

                            # 1st-party
                            build-server-rpm percona-dashboards grafana-dashboards
                            build-server-rpm pmm-managed
                            build-server-rpm percona-qan-api2 qan-api2
                            build-server-rpm pmm-server
                            build-server-rpm pmm-update
                            build-server-rpm dbaas-controller
                            build-server-rpm dbaas-tools

                            # 3rd-party
                            build-server-rpm victoriametrics
                            build-server-rpm alertmanager
                            build-server-rpm grafana
                        "
                    '''
                }
            }
        }
        stage('Build server docker') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        sg docker -c "
                            docker login -u "${USER}" -p "${PASS}"
                        "
                    """
                }
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        sg docker -c "
                            set -o errexit
                            aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                            export PUSH_DOCKER=1
                            export DOCKER_TAG=perconalab/pmm-server-fb:${BRANCH_NAME}-${GIT_COMMIT:0:7}

                            ./build/bin/build-server-docker
                        "
                    '''
                }
                stash includes: 'results/docker/TAG', name: 'IMAGE'
                archiveArtifacts 'results/docker/TAG'
            }
        }
        stage('Create FB tags')
        {
            steps{
                script{
                    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
                        unstash 'IMAGE'
                        def IMAGE = sh(returnStdout: true, script: "cat results/docker/TAG").trim()
                        def CLIENT_IMAGE = sh(returnStdout: true, script: "cat results/docker/CLIENT_TAG").trim()
                        def CLIENT_URL = sh(returnStdout: true, script: "cat CLIENT_URL").trim()
                        sh """
                            curl -v -X POST \
                                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                                -d "{\\"body\\":\\"server docker - ${IMAGE}\\nclient docker - ${CLIENT_IMAGE}\\nclient - ${CLIENT_URL}\\nCreate Staging Instance: https://pmm.cd.percona.com/job/aws-staging-start/parambuild/?DOCKER_VERSION=${IMAGE}&CLIENT_VERSION=${CLIENT_URL}\\"}" \
                                "https://api.github.com/repos/\$(echo $CHANGE_URL | cut -d '/' -f 4-5)/issues/${CHANGE_ID}/comments"
                        """
                    }
                }
            }
        }
        stage('Tests Execution') {
            parallel {
                stage('Test: API') {
                    steps {
                        script {
                            unstash 'IMAGE'
                            unstash 'apiBranch'
                            unstash 'apiCommitSha'
                            def IMAGE = sh(returnStdout: true, script: "cat results/docker/TAG").trim()
                            def CLIENT_IMAGE = sh(returnStdout: true, script: "cat results/docker/CLIENT_TAG").trim()
                            def OWNER = sh(returnStdout: true, script: "cat OWNER").trim()
                            def CLIENT_URL = sh(returnStdout: true, script: "cat CLIENT_URL").trim()
                            def API_TESTS_BRANCH = sh(returnStdout: true, script: "cat apiBranch").trim()
                            def GIT_COMMIT_HASH = sh(returnStdout: true, script: "cat apiCommitSha").trim()
                            runAPItests(IMAGE, API_TESTS_BRANCH, GIT_COMMIT_HASH, CLIENT_URL, OWNER)
                            if (!env.API_TESTS_RESULT.equals("SUCCESS")) {
                                sh "exit 1"
                            }
                        }
                    }
                }
                stage('Test: PMM-Testsuite') {
                    steps {
                        script {
                            unstash 'IMAGE'
                            unstash 'pmmQABranch'
                            unstash 'pmmQACommitSha'
                            def IMAGE = sh(returnStdout: true, script: "cat results/docker/TAG").trim()
                            def CLIENT_IMAGE = sh(returnStdout: true, script: "cat results/docker/CLIENT_TAG").trim()
                            def OWNER = sh(returnStdout: true, script: "cat OWNER").trim()
                            def CLIENT_URL = sh(returnStdout: true, script: "cat CLIENT_URL").trim()
                            def PMM_QA_GIT_BRANCH = sh(returnStdout: true, script: "cat pmmQABranch").trim()
                            def PMM_QA_GIT_COMMIT_HASH = sh(returnStdout: true, script: "cat pmmQACommitSha").trim()
                            runTestSuite(IMAGE, CLIENT_URL, PMM_QA_GIT_BRANCH, PMM_QA_GIT_COMMIT_HASH, env.PMM_VERSION)
                            if (!env.BATS_TESTS_RESULT.equals("SUCCESS")) {
                                sh "exit 1"
                            }
                        }
                    }
                }
                stage('Test: UI') {
                    steps {
                        script {
                            unstash 'IMAGE'
                            unstash 'pmmUITestBranch'
                            unstash 'pmmUITestsCommitSha'
                            def IMAGE = sh(returnStdout: true, script: "cat results/docker/TAG").trim()
                            def CLIENT_IMAGE = sh(returnStdout: true, script: "cat results/docker/CLIENT_TAG").trim()
                            def OWNER = sh(returnStdout: true, script: "cat OWNER").trim()
                            def CLIENT_URL = sh(returnStdout: true, script: "cat CLIENT_URL").trim()
                            def PMM_QA_GIT_BRANCH = sh(returnStdout: true, script: "cat pmmUITestBranch").trim()
                            def PMM_QA_GIT_COMMIT_HASH = sh(returnStdout: true, script: "cat pmmUITestsCommitSha").trim()
                            runUItests(IMAGE, CLIENT_URL, PMM_QA_GIT_BRANCH, PMM_QA_GIT_COMMIT_HASH)
                            if (!env.UI_TESTS_RESULT.equals("SUCCESS")) {
                                sh "exit 1"
                            }
                        }
                    }
                }
            }
        }
    }
    post {
        always {
            script {
                if (currentBuild.result == null || currentBuild.result == 'SUCCESS') {
                    if (env.CHANGE_URL) {
                        unstash 'IMAGE'
                        def IMAGE = sh(returnStdout: true, script: "cat results/docker/TAG").trim()
                        slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${IMAGE}"
                    }
                } else {
                    if(env.API_TESTS_RESULT != "SUCCESS") {
                        addComment("API tests have failed, Please check: API: ${API_TESTS_URL}")
                    }
                    if(env.BATS_TESTS_RESULT != "SUCCESS") {
                        addComment("pmm2-client testsuite has failed, Please check: BATS: ${BATS_TESTS_URL}")
                    }
                    if(env.UI_TESTS_RESULT != "SUCCESS") {
                        addComment("UI tests have failed, Please check: UI: ${UI_TESTS_URL}")
                    }
                    slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} build job link: ${BUILD_URL}"
                }
            }
            sh 'sudo make clean'
            deleteDir()
        }
    }
}

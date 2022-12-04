library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'agent-amd64'
    }
    environment {
        PATH_TO_SCRIPTS = 'sources/pmm/src/github.com/percona/pmm/build/scripts'
    }
    stages {
        stage('Prepare') {
            steps {
                withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
                sh '''
                    set -o errexit
                    if [ -s ci.yml ]; then
                        sudo rm -rf results tmp || :
                        git reset --hard
                        git clean -fdx
                        python3 ci.py
                        . ./.git-sources
                        echo $pmm_commit > apiCommitSha
                        echo $pmm_branch > apiBranch
                        echo $pmm_url > apiURL
                        echo $pmm_qa_branch > pmmQABranch
                        echo $pmm_qa_commit > pmmQACommitSha
                        echo $pmm_ui_tests_branch > pmmUITestBranch
                        echo $pmm_ui_tests_commit > pmmUITestsCommitSha
                    else
                        sudo rm -rf results tmp || :
                        git reset --hard
                        git clean -fdx
                        git submodule foreach --recursive git reset --hard
                        git submodule foreach --recursive git clean -fdx
                        git submodule status
                        export commit_sha=$(git submodule status | grep 'pmm-managed' | awk -F ' ' '{print $1}')
                        export api_tests_commit_sha=$(git submodule status | grep 'pmm' | awk -F ' ' '{print $1}')
                        export api_tests_branch=$(git config -f .gitmodules submodule.pmm.branch)
                        export api_tests_url=$(git config -f .gitmodules submodule.pmm.url)
                        echo $api_tests_commit_sha > apiCommitSha
                        echo $api_tests_branch > apiBranch
                        echo $api_tests_url > apiURL
                        cat apiBranch
                        cat apiURL
                        export pmm_qa_commit_sha=$(git submodule status | grep 'pmm-qa' | awk -F ' ' '{print $1}')
                        export pmm_qa_branch=$(git config -f .gitmodules submodule.pmm-qa.branch)
                        echo $pmm_qa_branch > pmmQABranch
                        echo $pmm_qa_commit_sha > pmmQACommitSha
                        export pmm_ui_tests_commit_sha=$(git submodule status | grep 'pmm-ui-tests' | awk -F ' ' '{print $1}')
                        export pmm_ui_tests_branch=$(git config -f .gitmodules submodule.pmm-ui-tests.branch)
                        echo $pmm_ui_tests_branch > pmmUITestBranch
                        echo $pmm_ui_tests_commit_sha > pmmUITestsCommitSha
                    fi
                    export fb_commit_sha=$(git rev-parse HEAD)
                    echo $fb_commit_sha > fbCommitSha
                '''
                }
                script {
                    env.PMM_VERSION = sh(returnStdout: true, script: "cat VERSION").trim()
                }
                stash includes: 'apiBranch', name: 'apiBranch'
                stash includes: 'apiURL', name: 'apiURL'
                stash includes: 'pmmQABranch', name: 'pmmQABranch'
                stash includes: 'apiCommitSha', name: 'apiCommitSha'
                stash includes: 'pmmQACommitSha', name: 'pmmQACommitSha'
                stash includes: 'pmmUITestBranch', name: 'pmmUITestBranch'
                stash includes: 'pmmUITestsCommitSha', name: 'pmmUITestsCommitSha'
                stash includes: 'fbCommitSha', name: 'fbCommitSha'
            }
        }
        stage('Build client source') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -o errexit

                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                        ${PATH_TO_SCRIPTS}/build-client-source
                    """
                }
            }
        }
        stage('Build client binary') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -o errexit

                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                        ${PATH_TO_SCRIPTS}/build-client-binary
                        aws s3 cp \
                            --acl public-read \
                            results/tarball/pmm2-client-*.tar.gz \
                            s3://pmm-build-cache/PR-BUILDS/pmm2-client/pmm2-client-\${BRANCH_NAME}-\${GIT_COMMIT:0:7}-arm.tar.gz
                    """
                }
                script {
                    def clientPackageURL = sh script:'echo "https://s3.us-east-2.amazonaws.com/pmm-build-cache/PR-BUILDS/pmm2-client/pmm2-client-${BRANCH_NAME}-${GIT_COMMIT:0:7}-arm.tar.gz" | tee CLIENT_URL', returnStdout: true
                    env.CLIENT_URL = sh(returnStdout: true, script: "cat CLIENT_URL").trim()
                }
                stash includes: 'CLIENT_URL', name: 'CLIENT_URL'
            }
        }
        stage('Build client source rpm') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -o errexit
                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                        ${PATH_TO_SCRIPTS}/build-client-srpm centos:7
                    """
                }
            }
        }
        stage('Build client binary rpm') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -o errexit

                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                        ${PATH_TO_SCRIPTS}/build-client-rpm centos:7

                        mkdir -p tmp/pmm-server/RPMS/
                        cp results/rpm/pmm2-client-*.rpm tmp/pmm-server/RPMS/
                    """
                }
            }
        }
        stage('Build client docker') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        docker login -u "${USER}" -p "${PASS}"
                    """
                }
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -o errexit
                        export PUSH_DOCKER=1
                        export DOCKER_CLIENT_TAG=perconalab/pmm-client-fb:\${BRANCH_NAME}-\${GIT_COMMIT:0:7}-arm
                        ${PATH_TO_SCRIPTS}/build-client-docker
                    """
                }
                stash includes: 'results/docker/CLIENT_TAG', name: 'CLIENT_IMAGE'
                archiveArtifacts 'results/docker/CLIENT_TAG'
            }
        }
        stage('Build server packages') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -o errexit

                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                        export RPM_EPOCH=1
                        export PATH=\$PATH:\$(pwd -P)/${PATH_TO_SCRIPTS}

                        ${PATH_TO_SCRIPTS}/build-server-rpm-all
                    """
                }
            }
        }
        stage('Build server docker') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        docker login -u "${USER}" -p "${PASS}"
                    """
                }
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -o errexit

                        export PUSH_DOCKER=1
                        export DOCKER_TAG=perconalab/pmm-server-fb:\${BRANCH_NAME}-\${GIT_COMMIT:0:7}-arm

                        ${PATH_TO_SCRIPTS}/build-server-docker
                    """
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
    }
}

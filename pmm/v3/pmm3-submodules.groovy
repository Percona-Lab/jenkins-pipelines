library changelog: false, identifier: 'lib@PMM-12557', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runAPItests(String DOCKER_IMAGE_VERSION, GIT_URL, GIT_BRANCH, GIT_COMMIT_HASH, CLIENT_VERSION) {
    apiTestJob = build job: 'pmm3-api-tests', propagate: false, parameters: [
        string(name: 'DOCKER_VERSION', value: DOCKER_IMAGE_VERSION),
        string(name: 'GIT_URL', value: GIT_URL),
        string(name: 'GIT_BRANCH', value: GIT_BRANCH),
        string(name: 'OWNER', value: "FB"),
        string(name: 'GIT_COMMIT_HASH', value: GIT_COMMIT_HASH)
    ]
    env.API_TESTS_URL = apiTestJob.absoluteUrl
    env.API_TESTS_RESULT = apiTestJob.result
}

void addComment(String COMMENT) {
    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
        sh '''
            REPO=$(echo $CHANGE_URL | cut -d '/' -f 4-5)
            curl -X POST \
                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                -d "{\\"body\\":\\"${COMMENT}\\"}" \
                "https://api.github.com/repos/${REPO}/issues/${CHANGE_ID}/comments"
        '''
    }
}

pipeline {
    agent {
        label 'agent-amd64'
    }
    parameters {
        choice(
            // default is choices.get(0) - el9
            choices: ['el9'],
            description: 'Select the OS to build for',
            name: 'BUILD_OS')
    }
    environment {
        PATH_TO_SCRIPTS = 'sources/pmm/src/github.com/percona/pmm/build/scripts'
        PMM_VER =  """${sh(returnStdout: true, script: "cat VERSION").trim()}"""
    }
    stages {
        stage('Prepare') {
            when {
                beforeAgent true
                expression {
                    env.PMM_VER =~ '^3.'
                }
            }
            steps {
                script {
                    addComment("Building PMM v3.x ...")
                }
                withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
                sh '''
                    set -o errexit
                    if [ -s ci.yml ]; then
                        sudo rm -rf results tmp || :
                        git reset --hard
                        git clean -fdx
                        python3 ci.py
                        cat .git-sources
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
                        export api_tests_commit_sha=$(git submodule status | grep 'sources/pmm/src' | awk -F ' ' '{print $1}')
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
                slackSend channel: '#pmm-ci', color: '#0000FF', message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
            }
        }
        stage('Build client source') {
            when {
                beforeAgent true
                expression {
                    env.PMM_VER =~ '^3.'
                }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        set -o errexit

                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                        ${PATH_TO_SCRIPTS}/build-client-source
                    '''
                }
            }
        }
        stage('Build client binary') {
            when {
                beforeAgent true
                expression {
                    env.PMM_VER =~ '^3.'
                }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -o errexit

                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                        ${PATH_TO_SCRIPTS}/build-client-binary
                        aws s3 cp \
                            --acl public-read \
                            results/tarball/pmm-client-*.tar.gz \
                            s3://pmm-build-cache/PR-BUILDS/pmm-client/pmm-client-\${BRANCH_NAME}-\${GIT_COMMIT:0:7}.tar.gz
                    """
                }
                script {
                    def clientPackageURL = sh script:'echo "https://s3.us-east-2.amazonaws.com/pmm-build-cache/PR-BUILDS/pmm-client/pmm-client-${BRANCH_NAME}-${GIT_COMMIT:0:7}.tar.gz" | tee CLIENT_URL', returnStdout: true
                    env.CLIENT_URL = sh(returnStdout: true, script: "cat CLIENT_URL").trim()
                }
                stash includes: 'CLIENT_URL', name: 'CLIENT_URL'
            }
        }
        stage('Build client source rpm') {
            when {
                beforeAgent true
                allOf{
                    expression{ params.BUILD_OS == "el9" }
                    expression{ env.PMM_VER =~ '^3.' }
                }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        set -o errexit
                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                        ${PATH_TO_SCRIPTS}/build-client-srpm public.ecr.aws/e7j3v3n0/rpmbuild:ol9
                    '''
                }
            }
        }
        stage('Build client binary rpm') {
            when {
                beforeAgent true
                allOf {
                    expression{ params.BUILD_OS == "el9" }
                    expression{ env.PMM_VER =~ '^3.' }
                }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        set -o errexit

                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                        ${PATH_TO_SCRIPTS}/build-client-rpm public.ecr.aws/e7j3v3n0/rpmbuild:ol9

                        mkdir -p tmp/pmm-server/RPMS/
                        cp results/rpm/pmm-client-*.rpm tmp/pmm-server/RPMS/
                    '''
                }
            }
        }
        stage('Build client docker') {
            when {
                beforeAgent true
                    expression {
                        env.PMM_VER =~ '^3.'
                    }
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh '''
                        docker login -u "${USER}" -p "${PASS}"
                    '''
                }
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -o errexit
                        export PUSH_DOCKER=1
                        export DOCKER_CLIENT_TAG=perconalab/pmm-client-fb:\${BRANCH_NAME}-\${GIT_COMMIT:0:7}
                        ${PATH_TO_SCRIPTS}/build-client-docker
                    """
                }
                stash includes: 'results/docker/CLIENT_TAG', name: 'CLIENT_IMAGE'
                archiveArtifacts 'results/docker/CLIENT_TAG'
            }
        }
        stage('Build server packages') {
            when {
                beforeAgent true
                allOf {
                    expression{ params.BUILD_OS == "el9" }
                    expression{ env.PMM_VER =~ '^3.' }
                }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''

                        set -o errexit

                        aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0

                        export RPM_EPOCH=1
                        export PATH=${PATH}:$(pwd -P)/${PATH_TO_SCRIPTS}
                        export RPMBUILD_DOCKER_IMAGE=public.ecr.aws/e7j3v3n0/rpmbuild:ol9
                        export RPMBUILD_DIST="el9"

                        ${PATH_TO_SCRIPTS}/build-server-rpm-all
                    '''
                }
            }
        }
        stage('Build server docker') {
            when {
                beforeAgent true
                allOf {
                    expression{ params.BUILD_OS == "el9" }
                    expression{ env.PMM_VER =~ '^3.' }
                }
            }
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh '''
                        docker login -u "${USER}" -p "${PASS}"
                    '''
                }
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        set -o errexit

                        export PUSH_DOCKER=1
                        export DOCKER_TAG=perconalab/pmm-server-fb:\${BRANCH_NAME}-\${GIT_COMMIT:0:7}

                        export RPMBUILD_DOCKER_IMAGE=public.ecr.aws/e7j3v3n0/rpmbuild:ol9
                        export RPMBUILD_DIST="el9"
                        export DOCKERFILE=Dockerfile.el9

                        ${PATH_TO_SCRIPTS}/build-server-docker
                    '''
                }
                stash includes: 'results/docker/TAG', name: 'IMAGE'
                archiveArtifacts 'results/docker/TAG'
            }
        }
        stage('Trigger workflows in GH')
        {
            when {
                beforeAgent true
                expression {
                    env.PMM_VER =~ '^3.'
                }
            }
            steps{
                script{
                    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
                        unstash 'IMAGE'
                        unstash 'pmmQABranch'
                        unstash 'pmmUITestBranch'
                        sh '''
                            IMAGE=$(cat results/docker/TAG | tr -d ' ')
                            CLIENT_IMAGE=$(cat results/docker/CLIENT_TAG | tr -d ' ')
                            CLIENT_URL=$(cat CLIENT_URL | tr -d ' ')
                            REPO=$(echo "$CHANGE_URL" | cut -d '/' -f 4-5)
                            BODY='{"body":"'
                            BODY+="Server docker: ${IMAGE}\\n"
                            BODY+="Client docker: ${CLIENT_IMAGE}\\n"
                            BODY+="Client tarball: ${CLIENT_URL}\\n"
                            BODY+="Staging instance: https://pmm.cd.percona.com/job/pmm3-aws-staging-start/parambuild/?DOCKER_VERSION=${IMAGE}&CLIENT_VERSION=${CLIENT_URL}"
                            BODY+='"}'
                            echo "$BODY"
                            # https://docs.github.com/en/rest/issues/comments?apiVersion=2022-11-28#create-an-issue-comment
                            curl -X POST \
                                -H "Accept: application/vnd.github+json" \
                                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                                -H "X-GitHub-Api-Version: 2022-11-28" \
                                -d "${BODY}" \
                                "https://api.github.com/repos/${REPO}/issues/${CHANGE_ID}/comments"
                        '''
                        def IMAGE = sh(returnStdout: true, script: "cat results/docker/TAG").trim()
                        def CLIENT_IMAGE = sh(returnStdout: true, script: "cat results/docker/CLIENT_TAG").trim()
                        def CLIENT_URL = sh(returnStdout: true, script: "cat CLIENT_URL").trim()
                        def FB_COMMIT_HASH = sh(returnStdout: true, script: "cat fbCommitSha").trim()
                        // trigger workflow in GH to run some test there as well, pass server and client images as parameters
                        sh """
                            curl -v -X POST \
                                -H "Accept: application/vnd.github.v3+json" \
                                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                                "https://api.github.com/repos/\$(echo $CHANGE_URL | cut -d '/' -f 4-5)/actions/workflows/jenkins-dispatch.yml/dispatches" \
                                -d '{"ref":"${CHANGE_BRANCH}","inputs":{"server_image":"${IMAGE}","client_image":"${CLIENT_IMAGE}","sha":"${FB_COMMIT_HASH}"}}'
                        """
                        // trigger workflow in GH to run PMM binary cli tests
                        sh """
                            curl -v -X POST \
                                -H "Accept: application/vnd.github.v3+json" \
                                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                                "https://api.github.com/repos/\$(echo $CHANGE_URL | cut -d '/' -f 4-5)/actions/workflows/pmm-cli.yml/dispatches" \
                                -d '{"ref":"${CHANGE_BRANCH}","inputs":{"client_tar_url":"${CLIENT_URL}","sha":"${FB_COMMIT_HASH}"}}'
                        """
                        // trigger workflow in GH to run testsuite tests
                        def PMM_QA_GIT_BRANCH = sh(returnStdout: true, script: "cat pmmQABranch").trim()
                        sh """
                            curl -v -X POST \
                                -H "Accept: application/vnd.github.v3+json" \
                                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                                "https://api.github.com/repos/\$(echo $CHANGE_URL | cut -d '/' -f 4-5)/actions/workflows/pmm-testsuite.yml/dispatches" \
                                -d '{"ref":"${CHANGE_BRANCH}","inputs":{"server_image":"${IMAGE}","client_image":"${CLIENT_IMAGE}","sha":"${FB_COMMIT_HASH}", "pmm_qa_branch": "${PMM_QA_GIT_BRANCH}", "client_version": "${CLIENT_URL}"}}'
                        """
                        // trigger workflow in GH to run ui tests
                        def PMM_UI_TESTS_GIT_BRANCH = sh(returnStdout: true, script: "cat pmmUITestBranch").trim()
                        sh """
                            curl -v -X POST \
                                -H "Accept: application/vnd.github.v3+json" \
                                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                                "https://api.github.com/repos/\$(echo $CHANGE_URL | cut -d '/' -f 4-5)/actions/workflows/pmm-ui-tests-fb.yml/dispatches" \
                                -d '{"ref":"${CHANGE_BRANCH}","inputs":{"server_image":"${IMAGE}","client_image":"${CLIENT_IMAGE}","sha":"${FB_COMMIT_HASH}", "pmm_qa_branch": "${PMM_QA_GIT_BRANCH}", "pmm_ui_branch": "${PMM_UI_TESTS_GIT_BRANCH}", "client_version": "${CLIENT_URL}"}}'
                        """
                        // trigger workflow in GH to run trivy for vulnerability scan
                        sh """
                            curl -v -X POST \
                                -H "Accept: application/vnd.github.v3+json" \
                                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                                "https://api.github.com/repos/\$(echo $CHANGE_URL | cut -d '/' -f 4-5)/actions/workflows/trivy_scan.yml/dispatches" \
                                -d '{"ref":"${CHANGE_BRANCH}","inputs":{"server_image":"${IMAGE}","client_image":"${CLIENT_IMAGE}","sha":"${FB_COMMIT_HASH}"}}'
                        """
                    }
                }
            }
        }
        stage('Tests Execution') {
            when {
                beforeAgent true
                expression {
                    env.PMM_VER =~ '^3.'
                }
            }
            parallel {
                stage('Test: API') {
                    steps {
                        script {
                            unstash 'IMAGE'
                            unstash 'apiURL'
                            unstash 'apiBranch'
                            unstash 'apiCommitSha'
                            def IMAGE = sh(returnStdout: true, script: "cat results/docker/TAG").trim()
                            def CLIENT_IMAGE = sh(returnStdout: true, script: "cat results/docker/CLIENT_TAG").trim()
                            def CLIENT_URL = sh(returnStdout: true, script: "cat CLIENT_URL").trim()
                            def API_TESTS_URL = sh(returnStdout: true, script: "cat apiURL").trim()
                            def API_TESTS_BRANCH = sh(returnStdout: true, script: "cat apiBranch").trim()
                            def GIT_COMMIT_HASH = sh(returnStdout: true, script: "cat apiCommitSha").trim()
                            runAPItests(IMAGE, API_TESTS_URL, API_TESTS_BRANCH, GIT_COMMIT_HASH, CLIENT_URL)
                            if (!env.API_TESTS_RESULT.equals("SUCCESS")) {
                                sh "exit 1"
                            }
                        }
                    }
                }
            }
        }
    }
    post {
        success {
            script {
                if (env.CHANGE_URL && env.PMM_VER =~ '^3.') {
                    unstash 'IMAGE'
                    def IMAGE = sh(returnStdout: true, script: "cat results/docker/TAG").trim()
                    slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - ${IMAGE}"
                }
            }
        }
        always {
            script {
                if (currentBuild.result != 'SUCCESS') {
                    if(env.API_TESTS_RESULT != "SUCCESS" && env.API_TESTS_URL) {
                        addComment("API tests have failed. Please check: API: ${API_TESTS_URL}")
                    }
                    if(env.BATS_TESTS_RESULT != "SUCCESS" && env.BATS_TESTS_URL) {
                        addComment("pmm-client testsuite has failed. Please check: BATS: ${BATS_TESTS_URL}")
                    }
                    if(env.UI_TESTS_RESULT != "SUCCESS" && env.UI_TESTS_URL) {
                        addComment("UI tests have failed. Please check: UI: ${UI_TESTS_URL}")
                    }
                    slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build ${currentBuild.result} build job link: ${BUILD_URL}"
                }
            }
        }
    }
}

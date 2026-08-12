library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

String buildOnlyList(String variants, String arches) {
    def selectors = []
    variants.split(',').each { variant ->
        arches.split(',').each { arch ->
            selectors << "${variant.trim()}.amazon-ebs.${variant.trim()}_${arch.trim()}"
        }
    }
    return selectors.join(',')
}

pipeline {
    agent {
        label 'min-ol-9-x64'
    }
    parameters {
        string(
            defaultValue: 'https://github.com/EvgeniyPatlan/valkey-packaging.git',
            description: 'URL for valkey-packaging repository',
            name: 'PACKAGING_REPO')
        string(
            defaultValue: 'main',
            description: 'Branch for valkey-packaging repository',
            name: 'PACKAGING_BRANCH')
        string(
            defaultValue: '9.1.1',
            description: 'Valkey version to install into the image',
            name: 'VALKEY_VERSION')
        choice(
            choices: 'release\ntesting\nexperimental',
            description: 'Percona repo channel for packages inside the image',
            name: 'REPO_CHANNEL')
        string(
            defaultValue: 'slim,bundle',
            description: 'Comma separated image variants to build',
            name: 'VARIANTS')
        string(
            defaultValue: 'x86_64,arm64',
            description: 'Comma separated architectures to build',
            name: 'ARCHES')
        string(
            defaultValue: '2',
            description: 'How many images packer builds at once. Lower values reduce load on the build agent.',
            name: 'PARALLEL_BUILDS')
        booleanParam(
            defaultValue: true,
            description: 'Copy the resulting AMIs to the region list in release.pkvars.hcl',
            name: 'COPY_REGIONS')
        booleanParam(
            defaultValue: false,
            description: 'Launch each AMI and run the post-launch smoke test. Requires SMOKE_KEY_NAME and SMOKE_SSH_CREDENTIAL to exist.',
            name: 'RUN_SMOKE')
        string(
            defaultValue: 'percona-images',
            description: 'EC2 key pair name used by the smoke test',
            name: 'SMOKE_KEY_NAME')
        string(
            defaultValue: 'percona-images-ssh',
            description: 'Jenkins ssh private key credential matching SMOKE_KEY_NAME',
            name: 'SMOKE_SSH_CREDENTIAL')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps()
    }
    environment {
        AWS_DEFAULT_REGION = 'us-east-1'
    }
    stages {
        stage('Checkout and prepare') {
            steps {
                slackNotify("#releases-ci", "#FFFF00", "[${JOB_NAME}]: starting AMI build for Valkey ${VALKEY_VERSION} - [${BUILD_URL}]")
                cleanUpWS()
                sh """
                    sudo yum -y install unzip make git
                    git clone ${PACKAGING_REPO} valkey-packaging
                    cd valkey-packaging
                    git checkout ${PACKAGING_BRANCH}
                    cd images
                    make deps
                """
            }
        }

        stage('Clean up orphans') {
            steps {
                withCredentials([[
                    $class: 'AmazonWebServicesCredentialsBinding',
                    accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                    credentialsId: 're-cd-aws',
                    secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        cd valkey-packaging/images
                        scripts/cleanup-orphans.sh ${AWS_DEFAULT_REGION} valkey-ami
                    """
                }
            }
        }

        stage('Validate') {
            steps {
                sh """
                    cd valkey-packaging/images
                    make validate
                """
            }
        }

        stage('Build AMIs') {
            steps {
                script {
                    def onlyList = buildOnlyList(params.VARIANTS, params.ARCHES)
                    def noCopy = params.COPY_REGIONS ? '' : "-var 'ami_regions=[]'"
                    echo "Building: ${onlyList}"

                    withCredentials([[
                        $class: 'AmazonWebServicesCredentialsBinding',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        credentialsId: 're-cd-aws',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                        sh """
                            set -o pipefail
                            cd valkey-packaging/images
                            ~/bin/packer build -color=false \
                              -parallel-builds=${params.PARALLEL_BUILDS} \
                              -only='${onlyList}' \
                              -var-file=packer/release.pkvars.hcl \
                              -var valkey_version=${params.VALKEY_VERSION} \
                              -var repo_channel=${params.REPO_CHANNEL} \
                              ${noCopy} \
                              packer/ | tee build.log
                        """
                    }
                }
            }
        }

        stage('Collect image ids') {
            steps {
                sh """
                    cd valkey-packaging/images
                    awk -v region="${AWS_DEFAULT_REGION}:" '
                        /^--> / { name = \$2; sub(/:\$/, "", name) }
                        \$1 == region { print name, \$2 }
                    ' build.log | sort -u > IMAGES
                    cat IMAGES
                    test -s IMAGES
                """
                archiveArtifacts 'valkey-packaging/images/IMAGES'
                archiveArtifacts 'valkey-packaging/images/build.log'
                script {
                    def images = readFile('valkey-packaging/images/IMAGES').trim()
                    currentBuild.description = "Valkey ${params.VALKEY_VERSION} (${params.REPO_CHANNEL})\n${images}"
                }
            }
        }

        stage('Smoke test') {
            when {
                expression { params.RUN_SMOKE }
            }
            steps {
                withCredentials([
                    [$class: 'AmazonWebServicesCredentialsBinding',
                     accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                     credentialsId: 're-cd-aws',
                     secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'],
                    sshUserPrivateKey(
                        credentialsId: params.SMOKE_SSH_CREDENTIAL,
                        keyFileVariable: 'SMOKE_KEY_FILE')]) {
                    sh '''
                        set -o pipefail
                        cd valkey-packaging/images

                        export SMOKE_SUBNET_ID=$(awk -F'"' '/subnet_id/ { print $2; exit }' packer/variables.pkr.hcl)
                        export SMOKE_SECURITY_GROUP_ID=$(awk -F'"' '/security_group_id/ { print $2; exit }' packer/variables.pkr.hcl)
                        export SMOKE_KEY_NAME="${SMOKE_KEY_NAME}"

                        failures=0
                        while read -r build ami; do
                            case "$build" in
                                *bundle*) variant=bundle ;;
                                *)        variant=slim ;;
                            esac
                            case "$build" in
                                *arm64*) instance_type=t4g.medium ;;
                                *)       instance_type=t3.medium ;;
                            esac
                            echo "=== smoke ${build} ${ami} (${variant}, ${instance_type}) ==="
                            if ! test/smoke/smoke.sh "$ami" "$AWS_DEFAULT_REGION" "$variant" "$instance_type"; then
                                failures=$((failures + 1))
                            fi
                        done < IMAGES

                        if [ "$failures" -gt 0 ]; then
                            echo "${failures} image(s) failed the smoke test"
                            exit 1
                        fi
                    '''
                }
            }
        }
    }

    post {
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: AMIs built for Valkey ${VALKEY_VERSION} - [${BUILD_URL}]")
            deleteDir()
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: AMI build failed for Valkey ${VALKEY_VERSION} - [${BUILD_URL}]")
            deleteDir()
        }
        always {
            script {
                // Best effort: when the agent itself was lost this cannot run,
                // which is why the same sweep also runs at the start of a build.
                try {
                    withCredentials([[
                        $class: 'AmazonWebServicesCredentialsBinding',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        credentialsId: 're-cd-aws',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                        sh """
                            cd valkey-packaging/images
                            scripts/cleanup-orphans.sh ${AWS_DEFAULT_REGION} valkey-ami
                        """
                    }
                } catch (err) {
                    echo "Orphan cleanup skipped: ${err}"
                }
                try {
                    sh 'sudo rm -rf ./*'
                } catch (err) {
                    echo "Workspace cleanup skipped: ${err}"
                }
            }
            deleteDir()
        }
    }
}

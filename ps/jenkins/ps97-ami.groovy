library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

String buildOnlyList(String arches) {
    def selectors = []
    arches.split(',').each { arch ->
        selectors << "ps97.amazon-ebs.ps97_${arch.trim()}"
    }
    return selectors.join(',')
}

pipeline {
    agent {
        label 'min-ol-9-x64'
    }
    parameters {
        string(
            defaultValue: 'https://github.com/Percona-Lab/percona-images.git',
            description: 'URL for percona-images repository',
            name: 'IMAGES_REPO')
        string(
            defaultValue: 'marketplace',
            description: 'Branch for percona-images repository',
            name: 'IMAGES_BRANCH')
        string(
            defaultValue: '9.7.1',
            description: 'Percona Server version to install into the image',
            name: 'PS_VERSION')
        choice(
            choices: 'release\ntesting\nexperimental',
            description: 'Percona repo channel for packages inside the image',
            name: 'REPO_CHANNEL')
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
                slackNotify("#releases-ci", "#FFFF00", "[${JOB_NAME}]: starting AMI build for Percona Server ${PS_VERSION} - [${BUILD_URL}]")
                cleanUpWS()
                sh """
                    sudo yum -y install unzip make git
                    git clone ${IMAGES_REPO} percona-images
                    cd percona-images
                    git checkout ${IMAGES_BRANCH}
                    cd images/ps97
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
                        cd percona-images/images/ps97
                        scripts/cleanup-orphans.sh ${AWS_DEFAULT_REGION} ps97-ami
                    """
                }
            }
        }

        stage('Validate') {
            steps {
                sh """
                    cd percona-images/images/ps97
                    make validate
                """
            }
        }

        stage('Build AMIs') {
            steps {
                script {
                    def onlyList = buildOnlyList(params.ARCHES)
                    def noCopy = params.COPY_REGIONS ? '' : "-var 'ami_regions=[]'"
                    echo "Building: ${onlyList}"

                    withCredentials([[
                        $class: 'AmazonWebServicesCredentialsBinding',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        credentialsId: 're-cd-aws',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                        sh """
                            set -o pipefail
                            cd percona-images/images/ps97
                            ~/bin/packer build -color=false \
                              -parallel-builds=${params.PARALLEL_BUILDS} \
                              -only='${onlyList}' \
                              -var-file=packer/release.pkvars.hcl \
                              -var ps97_version=${params.PS_VERSION} \
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
                    cd percona-images/images/ps97
                    awk -v region="${AWS_DEFAULT_REGION}:" '
                        /^--> / { name = \$2; sub(/:\$/, "", name) }
                        \$1 == region { print name, \$2 }
                    ' build.log | sort -u > IMAGES
                    cat IMAGES
                    test -s IMAGES
                """
                archiveArtifacts 'percona-images/images/ps97/IMAGES'
                archiveArtifacts 'percona-images/images/ps97/build.log'
                script {
                    def images = readFile('percona-images/images/ps97/IMAGES').trim()
                    currentBuild.description = "Percona Server ${params.PS_VERSION} (${params.REPO_CHANNEL})\n${images}"
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
                        cd percona-images/images/ps97

                        export SMOKE_SUBNET_ID=$(awk -F'"' '/subnet_id/ { print $2; exit }' packer/variables.pkr.hcl)
                        export SMOKE_SECURITY_GROUP_ID=$(awk -F'"' '/security_group_id/ { print $2; exit }' packer/variables.pkr.hcl)
                        export SMOKE_KEY_NAME="${SMOKE_KEY_NAME}"

                        failures=0
                        while read -r build ami; do
                            case "$build" in
                                *arm64*) instance_type=t4g.medium ;;
                                *)       instance_type=t3.medium ;;
                            esac
                            echo "=== smoke ${build} ${ami} (${instance_type}) ==="
                            if ! test/smoke/smoke.sh "$ami" "$AWS_DEFAULT_REGION" "$instance_type"; then
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
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: AMIs built for Percona Server ${PS_VERSION} - [${BUILD_URL}]")
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: AMI build failed for Percona Server ${PS_VERSION} - [${BUILD_URL}]")
        }
        always {
            // Every step here needs a live agent, so each is guarded
            // individually. A lost agent is exactly when this block runs and
            // exactly when it cannot do anything, which is why the orphan sweep
            // also runs at the start of the next build.
            script {
                try {
                    withCredentials([[
                        $class: 'AmazonWebServicesCredentialsBinding',
                        accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                        credentialsId: 're-cd-aws',
                        secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                        sh """
                            cd percona-images/images/ps97
                            scripts/cleanup-orphans.sh ${AWS_DEFAULT_REGION} ps97-ami
                        """
                    }
                } catch (err) {
                    echo "Orphan cleanup skipped: ${err}"
                }
                try {
                    sh 'sudo rm -rf ./*'
                    deleteDir()
                } catch (err) {
                    echo "Workspace cleanup skipped: ${err}"
                }
            }
        }
    }
}

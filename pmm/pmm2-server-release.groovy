library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    environment {
        specName = 'pmm2-release'
    }
    agent {
        label 'master'
    }
    parameters {
        choice(
            // default choice should be testing, since we publish RC on testing Repo
            choices: ['testing', 'experimental'],
            description: 'publish pmm2-server packages from testing repository',
            name: 'UPDATER_REPO')
        string(
            defaultValue: 'public.ecr.aws/e7j3v3n0/pmm-server:dev-latest',
            description: 'pmm-server container version (image-name:version-tag)',
            name: 'DOCKER_VERSION')
        string(
            defaultValue: '',
            description: 'OVA image filename',
            name: 'OVF_IMAGE')
        string(
            defaultValue: '',
            description: 'Amazon Machine Image (AMI) ID',
            name: 'AMI_ID')
        string(
            defaultValue: 'perconalab/pmm-client:dev-latest',
            description: 'pmm-client docker container version (image-name:version-tag)',
            name: 'DOCKER_CLIENT_VERSION')
        string(
            defaultValue: '2.0.0',
            description: 'PMM2 Server version',
            name: 'VERSION')
    }
    stages {
        stage('Get Docker RPMs') {
            agent {
                label 'min-centos-7-x64'
            }
            steps {
                installDocker()
                installAWSv2()
                slackSend botUser: true, channel: '#pmm-ci', color: '#FFFF00', message: "[${specName}]: build started - ${BUILD_URL}"
                withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                sh """
                    aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0
                    sg docker -c 'docker run ${DOCKER_VERSION} /usr/bin/rpm -qa' > rpms.list
                """
                }
                stash includes: 'rpms.list', name: 'rpms'
            }
        }
        stage('Get repo RPMs') {
            steps {
                unstash 'rpms'
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh '''
                        ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                            ls /srv/repo-copy/pmm2-components/yum/${UPDATER_REPO}/7/RPMS/x86_64 \
                            > repo.list
                        cat rpms.list \
                            | grep -v 'pmm2-client' \
                            | sed -e 's/[^A-Za-z0-9\\._+-]//g' \
                            | xargs -n 1 -I {} grep "^{}.rpm" repo.list \
                            | sort \
                            | tee copy.list
                    '''
                }
                stash includes: 'copy.list', name: 'copy'
                archiveArtifacts 'copy.list'
            }
        }
// Publish RPMs to repo.ci.percona.com
        stage('Copy RPMs to PMM repo') {
            steps {
                unstash 'copy'
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh '''
                        cat copy.list | ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                            "cat - | xargs -I{} cp -v /srv/repo-copy/pmm2-components/yum/${UPDATER_REPO}/7/RPMS/x86_64/{} /srv/repo-copy/pmm2-components/yum/release/7/RPMS/x86_64/{}"
                    '''
                }
            }
        }
        stage('Createrepo') {
            steps {
                withCredentials([string(credentialsId: 'SIGN_PASSWORD', variable: 'SIGN_PASSWORD')]) {
                    withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh """
                        ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com " \
                            createrepo --update /srv/repo-copy/pmm2-components/yum/release/7/RPMS/x86_64/
                            if [ -f /srv/repo-copy/pmm2-components/yum/release/7/RPMS/x86_64/repodata/repomd.xml.asc ]; then
                                rm -f /srv/repo-copy/pmm2-components/yum/release/7/RPMS/x86_64/repodata/repomd.xml.asc
                            fi
                            export SIGN_PASSWORD=\${SIGN_PASSWORD}
                            gpg --detach-sign --armor --passphrase \${SIGN_PASSWORD} /srv/repo-copy/pmm2-components/yum/release/7/RPMS/x86_64/repodata/repomd.xml
                        "
                    """
                    }
                }
            }
        }
// Publish RPMs to repo.percona.com
        stage('Publish RPMs') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh """
                        ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com "
                            rsync -avt --bwlimit=50000 --delete --progress --exclude=rsync-* --exclude=*.bak \
                                /srv/repo-copy/pmm2-components/yum/release \
                                10.10.9.209:/www/repo.percona.com/htdocs/pmm2-components/yum/
                            bash +x /usr/local/bin/clear_cdn_cache.sh
                        "
                    """
                }
            }
        }
        stage('Set Tags') {
            steps {
                withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
                    unstash 'copy'
                    sh """
                        echo ${GITHUB_API_TOKEN} > GITHUB_API_TOKEN
                        echo ${VERSION} > VERSION
                    """
                    sh '''
                        set -ex
                        export VERSION=$(cat VERSION)
                        export TOP_VER=$(cat VERSION | cut -d. -f1)
                        export MID_VER=$(cat VERSION | cut -d. -f2)
                        export DOCKER_MID="$TOP_VER.$MID_VER"
                        declare -A repo=(
                            ["percona-dashboards"]="percona/grafana-dashboards"
                            ["pmm-server"]="percona/pmm-server"
                            ["percona-qan-api2"]="percona/qan-api2"
                            ["pmm-update"]="percona/pmm-update"
                            ["pmm-managed"]="percona/pmm-managed"
                        )

                        for package in "${!repo[@]}"; do
                            SHA=$(
                                grep "^$package-$VERSION-" copy.list \
                                    | perl -p -e 's/.*[.]\\d{10}[.]([0-9a-f]{7})[.]el7.*/$1/'
                            )
                            if [[ -n "$package" ]] && [[ -n "$SHA" ]]; then
                                rm -fr $package
                                mkdir $package
                                pushd $package >/dev/null
                                    git clone https://github.com/${repo["$package"]} ./
                                    git checkout $SHA
                                    FULL_SHA=$(git rev-parse HEAD)

                                    echo "$FULL_SHA"
                                    echo "$VERSION"
                                popd >/dev/null
                            fi
                        done
                    '''
                }
                stash includes: 'VERSION', name: 'version_file'
            }
        }
        stage('Set Docker Tag') {
            agent {
                label 'min-centos-7-x64'
            }
            steps {
                unstash 'version_file'
                installDocker()
                installAWSv2()
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        sg docker -c "
                            echo "${PASS}" | docker login -u "${USER}" --password-stdin
                        "
                    """
                }
                withCredentials([aws(accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'ECRRWUser', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    sh """
                        VERSION=\$(cat VERSION)
                        TOP_VER=\$(cat VERSION | cut -d. -f1)
                        MID_VER=\$(cat VERSION | cut -d. -f2)
                        DOCKER_MID="\$TOP_VER.\$MID_VER"
                        sg docker -c "
                            set -ex
                            aws ecr-public get-login-password --region us-east-1 | docker login -u AWS --password-stdin public.ecr.aws/e7j3v3n0
                            # push pmm-server
                            docker pull \${DOCKER_VERSION}
                            docker tag \${DOCKER_VERSION} percona/pmm-server:latest
                            docker push percona/pmm-server:latest

                            docker tag \${DOCKER_VERSION} percona/pmm-server:\${TOP_VER}
                            docker tag \${DOCKER_VERSION} percona/pmm-server:\${DOCKER_MID}
                            docker tag \${DOCKER_VERSION} percona/pmm-server:\${VERSION}
                            docker push percona/pmm-server:\${TOP_VER}
                            docker push percona/pmm-server:\${DOCKER_MID}
                            docker push percona/pmm-server:\${VERSION}

                            docker tag \${DOCKER_VERSION} perconalab/pmm-server:\${TOP_VER}
                            docker tag \${DOCKER_VERSION} perconalab/pmm-server:\${DOCKER_MID}
                            docker tag \${DOCKER_VERSION} perconalab/pmm-server:\${VERSION}
                            docker push perconalab/pmm-server:\${TOP_VER}
                            docker push perconalab/pmm-server:\${DOCKER_MID}
                            docker push perconalab/pmm-server:\${VERSION}

                            docker save percona/pmm-server:\${VERSION} | xz > pmm-server-\${VERSION}.docker

                            # push pmm-client
                            docker pull \${DOCKER_CLIENT_VERSION}
                            docker tag \${DOCKER_CLIENT_VERSION} percona/pmm-client:latest
                            docker push percona/pmm-client:latest

                            docker tag \${DOCKER_CLIENT_VERSION} percona/pmm-client:\${TOP_VER}
                            docker tag \${DOCKER_CLIENT_VERSION} percona/pmm-client:\${DOCKER_MID}
                            docker tag \${DOCKER_CLIENT_VERSION} percona/pmm-client:\${VERSION}
                            docker push percona/pmm-client:\${TOP_VER}
                            docker push percona/pmm-client:\${DOCKER_MID}
                            docker push percona/pmm-client:\${VERSION}

                            docker tag \${DOCKER_CLIENT_VERSION} perconalab/pmm-client:\${TOP_VER}
                            docker tag \${DOCKER_CLIENT_VERSION} perconalab/pmm-client:\${DOCKER_MID}
                            docker tag \${DOCKER_CLIENT_VERSION} perconalab/pmm-client:\${VERSION}
                            docker push perconalab/pmm-client:\${TOP_VER}
                            docker push perconalab/pmm-client:\${DOCKER_MID}
                            docker push perconalab/pmm-client:\${VERSION}

                            docker save percona/pmm-client:\${VERSION} | xz > pmm-client-\${VERSION}.docker
                        "
                    """
                }
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -ex
                        aws s3 cp --only-show-errors pmm-server-\${VERSION}.docker s3://percona-vm/pmm-server-\${VERSION}.docker
                        aws s3 cp --only-show-errors pmm-client-\${VERSION}.docker s3://percona-vm/pmm-client-\${VERSION}.docker
                    """
                }
                deleteDir()
            }
        }
        stage('Publish Docker image') {
            agent {
                label 'virtualbox'
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -ex
                        aws s3 cp --only-show-errors s3://percona-vm/pmm-server-\${VERSION}.docker pmm-server-\${VERSION}.docker
                        aws s3 cp --only-show-errors s3://percona-vm/pmm-client-\${VERSION}.docker pmm-client-\${VERSION}.docker
                    """
                }
                sh """
                    ssh -i ~/.ssh/id_rsa_downloads -p 2222 jenkins@jenkins-deploy.jenkins-deploy.web.r.int.percona.com "mkdir -p /data/downloads/pmm2/\${VERSION}/docker" || true
                    sha256sum pmm-server-\${VERSION}.docker > pmm-server-\${VERSION}.sha256sum
                    sha256sum pmm-client-\${VERSION}.docker > pmm-client-\${VERSION}.sha256sum
                    scp -i ~/.ssh/id_rsa_downloads -P 2222 pmm-server-\${VERSION}.docker pmm-server-\${VERSION}.sha256sum jenkins@jenkins-deploy.jenkins-deploy.web.r.int.percona.com:/data/downloads/pmm2/\${VERSION}/docker/
                    scp -i ~/.ssh/id_rsa_downloads -P 2222 pmm-client-\${VERSION}.docker pmm-client-\${VERSION}.sha256sum jenkins@jenkins-deploy.jenkins-deploy.web.r.int.percona.com:/data/downloads/pmm2/\${VERSION}/docker/
                """
                deleteDir()
            }
        }
        stage('Refresh website part 1') {
            agent {
                label 'virtualbox'
            }
            steps {
                sh """
                    until curl https://www.percona.com/admin/config/percona/percona_downloads/crawl_directory > /tmp/crawler; do
                        tail /tmp/crawler
                        sleep 10
                    done
                    tail /tmp/crawler
                """
            }
        }
        stage('Publish OVF image') {
            agent {
                label 'virtualbox'
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        aws s3 cp --only-show-errors s3://percona-vm/\${OVF_IMAGE} pmm-server-\${VERSION}.ova
                    """
                }
                sh """
                    ssh -i ~/.ssh/id_rsa_downloads -p 2222 jenkins@jenkins-deploy.jenkins-deploy.web.r.int.percona.com "mkdir -p /data/downloads/pmm2/\${VERSION}/ova" || true
                    sha256sum pmm-server-\${VERSION}.ova > pmm-server-\${VERSION}.sha256sum
                    scp -i ~/.ssh/id_rsa_downloads -P 2222 pmm-server-\${VERSION}.ova pmm-server-\${VERSION}.sha256sum jenkins@jenkins-deploy.jenkins-deploy.web.r.int.percona.com:/data/downloads/pmm2/\${VERSION}/ova/
                """
                deleteDir()
            }
        }
        stage('Copy AMI') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -ex
                        get_image_id() {
                            aws ec2 describe-images \
                                --owners self \
                                --filters "Name=name,Values=\$1" \
                                --query 'Images[].ImageId' \
                                --output text \
                                --region \$2 \
                                | tr '\t' '\n' \
                                | sort -k 4 \
                                | tail -1
                        }
                        SOURCE_REGION="us-east-1"
                        IMAGE_NAME=\$(
                            aws ec2 describe-images \
                            --image-ids ${AMI_ID} \
                            --query 'Images[].Name' \
                            --region "\$SOURCE_REGION" \
                            --output text \
                            || :
                        )
                        if [ -z "\$IMAGE_NAME" ]; then
                            echo Cannot find ${AMI_ID} AMI | tee ami.list
                            exit 0
                        fi
                        IMAGE_NAME_REGEX=\$(echo \$IMAGE_NAME | sed -e 's/]\$/*/')
                        rm -rf ami.list || :
                        for REGION in \$(aws ec2 describe-regions --query 'Regions[].RegionName' --region "\$SOURCE_REGION" --output text | tr '\t' '\n' | sort); do
                            COPY_IMAGE_ID=\$(get_image_id "\$IMAGE_NAME_REGEX" "\$REGION")
                            if [ -z "\$COPY_IMAGE_ID" ]; then
                                COPY_IMAGE_ID=\$(
                                    aws ec2 copy-image \
                                        --source-region "\$SOURCE_REGION" \
                                        --source-image-id ${AMI_ID} \
                                        --name "\$IMAGE_NAME" \
                                        --region "\$REGION" \
                                        --output text
                                )
                            fi
                            printf "%-20s %-20s\n" "\$REGION" "\$COPY_IMAGE_ID" >> ami.list
                        done
                    """
                    archiveArtifacts 'ami.list'
                }
            }
        }
        stage('Sleep') {
            steps {
                sleep 600
            }
        }
        stage('Publish AMI') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        set -ex
                        get_image_id() {
                            aws ec2 describe-images \
                                --owners self \
                                --filters "Name=name,Values=\$1" \
                                --query 'Images[].ImageId' \
                                --output text \
                                --region \$2 \
                                | tr '\t' '\n' \
                                | sort -k 4 \
                                | tail -1
                        }
                        SOURCE_REGION="us-east-1"
                        IMAGE_NAME=\$(
                            aws ec2 describe-images \
                            --image-ids ${AMI_ID} \
                            --query 'Images[].Name' \
                            --region "\$SOURCE_REGION" \
                            --output text \
                            || :
                        )
                        if [ -z "\$IMAGE_NAME" ]; then
                            echo Cannot find ${AMI_ID} AMI | tee ami.list
                            exit 0
                        fi
                        IMAGE_NAME_REGEX=\$(echo \$IMAGE_NAME | sed -e 's/]\$/*/')

                        for REGION in \$(aws ec2 describe-regions --query 'Regions[].RegionName' --region "\$SOURCE_REGION" --output text | tr '\t' '\n' | sort); do
                            COPY_IMAGE_ID=\$(get_image_id "\$IMAGE_NAME_REGEX" "\$REGION")
                            while ! aws ec2 modify-image-attribute --image-id "\$COPY_IMAGE_ID" --region "\$REGION" --launch-permission "{\\"Add\\": [{\\"Group\\":\\"all\\"}]}"; do
                                sleep 60
                                COPY_IMAGE_ID=\$(get_image_id "\$IMAGE_NAME_REGEX" "\$REGION")
                            done
                        done
                    """
                }
            }
        }
        stage('Refresh website part 2') {
            agent {
                label 'virtualbox'
            }
            steps {
                sh """
                    until curl https://www.percona.com/admin/config/percona/percona_downloads/crawl_directory > /tmp/crawler; do
                        tail /tmp/crawler
                        sleep 10
                    done
                    tail /tmp/crawler
                """
            }
        }
    }
    post {
        always {
            deleteDir()
        }
        success {
            unstash 'copy'
            script {
                def IMAGE = sh(returnStdout: true, script: "cat copy.list").trim()
                slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${specName}]: build finished - ${IMAGE}"
            }
        }
        failure {
            slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${specName}]: build failed"
        }
    }
}

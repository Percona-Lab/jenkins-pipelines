library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    environment {
        specName = 'pmm-release'
    }
    agent {
        label 'master'
    }
    parameters {
        string(
            defaultValue: 'perconalab/pmm-server:dev-latest',
            description: 'PMM Server docker container version (image-name:version-tag)',
            name: 'DOCKER_VERSION')
        string(
            defaultValue: '',
            description: 'Amazon Machine Image (AMI) ID',
            name: 'AMI_VERSION')
        string(
            defaultValue: '',
            description: 'OVA image filename',
            name: 'OVF_VERSION')
        string(
            defaultValue: '1.8.0',
            description: 'PMM Server version',
            name: 'VERSION')
    }
    stages {
        stage('Get Docker RPMs') {
            agent {
                label 'min-centos-7-x64'
            }
            steps {
                installDocker()
                slackSend channel: '#pmm-ci', color: '#FFFF00', message: "[${specName}]: build started - ${BUILD_URL}"
                sh "sg docker -c 'docker run ${DOCKER_VERSION} /usr/bin/rpm -qa' > rpms.list"
                stash includes: 'rpms.list', name: 'rpms'
            }
        }
        stage('Get repo RPMs') {
            steps {
                unstash 'rpms'
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh '''
                        ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                            ls /srv/repo-copy/laboratory/7/RPMS/x86_64 \
                            > repo.list
                        cat rpms.list \
                            | sed -e 's/[^A-Za-z0-9\\._+-]//g' \
                            | xargs -n 1 -I {} grep "^{}.rpm" repo.list \
                            | tee copy.list
                    '''
                }
                stash includes: 'copy.list', name: 'copy'
                archiveArtifacts 'copy.list'
            }
        }
        stage('Copy RPMs to PMM repo') {
            steps {
                unstash 'copy'
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh '''
                        cat copy.list | ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                            "cat - | xargs -I{} cp -v /srv/repo-copy/laboratory/7/RPMS/x86_64/{} /srv/repo-copy/pmm/7/RPMS/x86_64/{}"
                    '''
                }
            }
        }
        stage('Createrepo') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh '''
                        ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com \
                            createrepo --update /srv/repo-copy/pmm/7/RPMS/x86_64/
                    '''
                }
            }
        }
        stage('Publish RPMs') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'repo.ci.percona.com', keyFileVariable: 'KEY_PATH', usernameVariable: 'USER')]) {
                    sh """
                        ssh -o StrictHostKeyChecking=no -i ${KEY_PATH} ${USER}@repo.ci.percona.com "
                            rsync -avt --bwlimit=50000 --delete --progress --exclude=rsync-* --exclude=*.bak \
                                /srv/repo-copy/pmm/ \
                                10.10.9.209:/www/repo.percona.com/htdocs/pmm/
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
                        export VERSION=$(cat VERSION)
                        declare -A repo=(
                            ["percona-dashboards"]="percona/grafana-dashboards"
                            ["pmm-server"]="percona/pmm-server"
                            ["percona-qan-api"]="percona/qan-api"
                            ["percona-qan-app"]="percona/qan-app"
                            ["pmm-update"]="percona/pmm-update"
                            ["pmm-manage"]="percona/pmm-manage"
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

                                    set +o xtrace
                                        curl -X POST \
                                            -H "Authorization: token $(cat ../GITHUB_API_TOKEN)" \
                                            -d "{\\"ref\\":\\"refs/tags/v${VERSION}\\",\\"sha\\": \\"${FULL_SHA}\\"}" \
                                            https://api.github.com/repos/${repo["$package"]}/git/refs
                                    set -o xtrace
                                popd >/dev/null
                            fi
                        done
                    '''
                }
            }
        }
        stage('Set Docker Tag') {
            agent {
                label 'min-centos-7-x64'
            }
            steps {
                installDocker()
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        sg docker -c "
                            docker login -u "${USER}" -p "${PASS}"
                        "
                    """
                }
                sh """
                    sg docker -c "
                        docker pull ${DOCKER_VERSION}
                        docker tag ${DOCKER_VERSION} percona/pmm-server:${VERSION}
                        docker tag ${DOCKER_VERSION} percona/pmm-server:latest
                        docker push percona/pmm-server:${VERSION}
                        docker push percona/pmm-server:latest
                        docker save percona/pmm-server:${VERSION} | xz > pmm-server-${VERSION}.docker
                    "
                """
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        aws s3 cp --only-show-errors pmm-server-${VERSION}.docker s3://percona-vm/pmm-server-${VERSION}.docker
                    """
                }
                deleteDir()
            }
        }
        stage('Publish OVF') {
            agent {
                label 'virtualbox'
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        aws s3 cp --only-show-errors s3://percona-vm/${OVF_VERSION} pmm-server-${VERSION}.ova
                        aws s3 cp --only-show-errors s3://percona-vm/pmm-server-${VERSION}.docker pmm-server-${VERSION}.docker
                    """
                }
                sh """
                    ssh -i ~/.ssh/id_rsa_downloads jenkins@10.10.9.216 "mkdir -p /data/downloads/pmm/${VERSION}/{ova,docker}" || true
                    md5sum pmm-server-${VERSION}.docker > pmm-server-${VERSION}.md5sum
                    scp -i ~/.ssh/id_rsa_downloads pmm-server-${VERSION}.docker pmm-server-${VERSION}.md5sum jenkins@10.10.9.216:/data/downloads/pmm/${VERSION}/docker/

                    md5sum pmm-server-${VERSION}.ova > pmm-server-${VERSION}.md5sum
                    scp -i ~/.ssh/id_rsa_downloads pmm-server-${VERSION}.ova pmm-server-${VERSION}.md5sum jenkins@10.10.9.216:/data/downloads/pmm/${VERSION}/ova/

                    until curl https://www.percona.com/admin/config/percona/percona_downloads/crawl_directory > /tmp/crawler; do
                        tail /tmp/crawler
                        sleep 10
                    done
                    tail /tmp/crawler
                """
                deleteDir()
            }
        }
        stage('Copy AMI') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
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
                            --image-ids ${AMI_VERSION} \
                            --query 'Images[].Name' \
                            --region "\$SOURCE_REGION" \
                            --output text \
                            || :
                        )
                        if [ -z "\$IMAGE_NAME" ]; then
                            echo Cannot find ${AMI_VERSION} AMI | tee ami.list
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
                                        --source-image-id ${AMI_VERSION} \
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
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'AMI/OVF', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
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
                            --image-ids ${AMI_VERSION} \
                            --query 'Images[].Name' \
                            --region "\$SOURCE_REGION" \
                            --output text \
                            || :
                        )
                        if [ -z "\$IMAGE_NAME" ]; then
                            echo Cannot find ${AMI_VERSION} AMI | tee ami.list
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
    }

    post {
        always {
            deleteDir()
        }
        success {
            unstash 'copy'
            script {
                def IMAGE = sh(returnStdout: true, script: "cat copy.list").trim()
                slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${specName}]: build finished - ${IMAGE}"
            }
        }
        failure {
            slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${specName}]: build failed"
        }
    }
}

library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: '',
            description: 'Amazon Machine Image (AMI) ID',
            name: 'AMI_ID')
    }
    stages {
        stage('Copy AMI') {
            steps {
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
        stage('Sleep') {
            steps {
                sleep 300
            }
        }
        stage('Publish AMI') {
            steps {
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
    post {
        always {
            deleteDir()
        }
        success {

            slackSend botUser: true,
                        channel: '#pmm-ci',
                        color: '#00FF00',
                        message: "PMM AMI was released!"
        }
        failure {
            slackSend botUser: true,
                      channel: '#pmm-ci',
                      color: '#FF0000',
                      message: "[${specName}]: build failed"
        }
    }
}

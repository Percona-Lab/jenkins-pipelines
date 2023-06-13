pipeline {
    agent {
        label 'ovf-do'
    }
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pmm repository',
            name: 'PMM_BRANCH')
        choice(
            choices: ['no', 'yes'],
            description: "Build Release Candidate?",
            name: 'RELEASE_CANDIDATE')
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
        parallelsAlwaysFailFast()
    }
    triggers {
        upstream upstreamProjects: 'pmm2-server-autobuild', threshold: hudson.model.Result.SUCCESS
    }

    stages {
        stage('Prepare') {
            steps {
                script {
                    env.PMM_VERSION = 'dev-latest'
                    if (params.RELEASE_CANDIDATE == 'yes') {
                        // release branch should be in the format: pmm-2.x.y
                        env.PMM_VERSION = PMM_BRANCH.split('-')[1] 
                    }
                }
                withCredentials([string(credentialsId: '82c0e9e0-75b5-40ca-8514-86eca3a028e0', variable: 'DIGITALOCEAN_ACCESS_TOKEN')]) {
                    sh '''
                        set -o xtrace

                        # https://docs.digitalocean.com/products/droplets/how-to/retrieve-droplet-metadata/
                        DROPLET_ID=$(curl -s http://169.254.169.254/metadata/v1/id)
                        FIREWALL_ID=$(doctl compute firewall list -o json | jq -r '.[] | select(.name=="pmm-firewall") | .id')
                        doctl compute firewall add-droplets $FIREWALL_ID --droplet-ids $DROPLET_ID
                    '''
                }                
                slackSend botUser: true,
                          channel: '#pmm-ci',
                          color: '#0000FF',
                          message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                checkout([$class: 'GitSCM', 
                          branches: [[name: "*/${PMM_BRANCH}"]],
                          extensions: [[$class: 'CloneOption',
                          noTags: true,
                          reference: '',
                          shallow: true]],
                          userRemoteConfigs: [[url: 'https://github.com/percona/pmm.git']]])
                dir('build') {
                    sh '''
                        make fetch
                    '''
                }
                sh '''
                    mkdir -p build/update
                    # copy update playbook to `build` to not have to pull it from pmm-update
                    cp -rpav update/ansible/playbook/* build/update
                '''

            }
        }

        stage('Build Release Candidate Images') {
            when {
                expression { params.RELEASE_CANDIDATE == "yes" }
            }
            parallel {
                stage('Build Release Candidate Image EL7') {
                    steps {
                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            dir("build") {
                                sh """
                                    /usr/bin/packer build \
                                    -var 'pmm_client_repos=original testing' \
                                    -var 'pmm_client_repo_name=percona-testing-x86_64' \
                                    -var 'pmm2_server_repo=testing' \
                                    -only virtualbox-ovf -color=false packer/pmm2.json \
                                        | tee build.log
                                """
                            }
                        }
                        sh 'ls */*/PMM2-Server-EL7*.ova | cut -d "/" -f 2 > IMAGE_EL7'
                        stash includes: 'IMAGE_EL7', name: 'IMAGE_EL7'
                        archiveArtifacts 'IMAGE_EL7'
                    }
                }
                stage('Build Release Candidate Image EL9') {
                    steps {
                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            dir('build') {
                                sh '''
                                    make pmm2-ovf-el9-rc
                                '''
                            }
                        }
                        sh 'ls */*/PMM2-Server-EL9*.ova | cut -d "/" -f 2 > IMAGE'
                    }
                }
            }
        }
        stage('Build Dev-Latest Images') {
            when {
                expression { params.RELEASE_CANDIDATE == "no" }
            }
            parallel {
                stage('Build Dev-Latest Image EL7') {
                    steps {
                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            dir("build") {
                                sh """
                                    /usr/bin/packer build \
                                    -var 'pmm_client_repos=original experimental' \
                                    -var 'pmm_client_repo_name=percona-experimental-x86_64' \
                                    -var 'pmm2_server_repo=experimental' \
                                    -only virtualbox-ovf -color=false packer/pmm2.json \
                                        | tee build.log
                                """
                            }
                        }
                        sh 'ls */*/PMM2-Server-EL7*.ova | cut -d "/" -f 2 > IMAGE_EL7'
                        stash includes: 'IMAGE_EL7', name: 'IMAGE_EL7'
                        archiveArtifacts 'IMAGE_EL7'
                    }
                }
                stage('Build Dev-Latest Image EL9') {
                    steps {
                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            dir('build') {
                                sh '''
                                    make pmm2-ovf-el9-dev-latest
                                '''
                            }
                        }
                        sh 'ls */*/PMM2-Server-EL9*.ova | cut -d "/" -f 2 > IMAGE'
                    }
                }
            }
        }

        stage('Upload Release Candidate Images') {
            when {
                expression { params.RELEASE_CANDIDATE == "yes" }
            }
            parallel {
                stage('Upload Release Candidate Image EL7') {
                    steps {
                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            sh """
                                FILE=\$(ls */*/PMM2-Server-EL7*.ova)
                                NAME=\$(basename \${FILE})
                                aws s3 cp \
                                    --only-show-errors \
                                    --acl public-read \
                                    \${FILE} \
                                    s3://percona-vm/\${NAME}

                                aws s3 cp \
                                    --only-show-errors \
                                    --acl public-read \
                                    s3://percona-vm/\${NAME} \
                                    s3://percona-vm/PMM2-Server-${PMM_VERSION}.el7.ova
                            """
                        }
                        script {
                            env.PMM2_SERVER_EL7_OVA_S3 = "https://percona-vm.s3.amazonaws.com/PMM2-Server-${PMM_VERSION}.el7.ova"
                        }
                    }
                }
                stage('Upload Release Candidate Image EL9') {
                    steps {
                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            sh '''
                                FILE=$(ls */*/PMM2-Server-EL9*.ova)
                                NAME=$(basename ${FILE})
                                aws s3 cp \
                                    --only-show-errors \
                                    --acl public-read \
                                    ${FILE} \
                                    s3://percona-vm/${NAME}

                                aws s3 cp \
                                    --only-show-errors \
                                    --acl public-read \
                                    s3://percona-vm/${NAME} \
                                    s3://percona-vm/PMM2-Server-${PMM_VERSION}.ova
                            '''
                        }
                        script {
                            env.PMM2_SERVER_OVA_S3 = "https://percona-vm.s3.amazonaws.com/PMM2-Server-${PMM_VERSION}.ova"
                        }
                    }
                }
            }
        }
        stage('Upload Dev-Latest Images') {
            when {
                expression { params.RELEASE_CANDIDATE == "no" }
            }
            parallel {
                stage('Upload Dev-Latest Image EL7') {
                    steps {
                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            sh """
                                FILE=\$(ls */*/PMM2-Server-EL7*.ova)
                                NAME=\$(basename \${FILE})
                                aws s3 cp \
                                    --only-show-errors \
                                    --acl public-read \
                                    \${FILE} \
                                    s3://percona-vm/\${NAME}

                                echo /\${NAME} > PMM2-Server-dev-latest.el7.ova
                                aws s3 cp \
                                    --only-show-errors \
                                    --website-redirect /\${NAME} \
                                    PMM2-Server-dev-latest.el7.ova \
                                    s3://percona-vm/PMM2-Server-dev-latest.el7.ova
                            """
                        }
                        script {
                            env.PMM2_SERVER_EL7_OVA_S3 = "http://percona-vm.s3-website-us-east-1.amazonaws.com/PMM2-Server-dev-latest.el7.ova"
                        }
                    }
                }
                stage('Upload Dev-Latest Image EL9') {
                    steps {
                        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                            sh '''
                                FILE=$(ls */*/PMM2-Server-EL9*.ova)
                                NAME=$(basename ${FILE})
                                aws s3 cp \
                                    --only-show-errors \
                                    --acl public-read \
                                    ${FILE} \
                                    s3://percona-vm/${NAME}

                                # This will redirect to the image above
                                echo /${NAME} > PMM2-Server-dev-latest.ova
                                aws s3 cp \
                                    --only-show-errors \
                                    --website-redirect /${NAME} \
                                    PMM2-Server-dev-latest.ova \
                                    s3://percona-vm/PMM2-Server-dev-latest.ova
                            '''
                        }
                        script {
                            env.PMM2_SERVER_OVA_S3 = "http://percona-vm.s3-website-us-east-1.amazonaws.com/PMM2-Server-dev-latest.ova"
                        }
                    }
                }
            }
        }
    }

    post {
        success {
            script {
                if (params.RELEASE_CANDIDATE == "yes")
                {
                    currentBuild.description = "RC Build, EL9 Image: " + env.PMM2_SERVER_OVA_S3 + " EL7 Image: " + env.PMM2_SERVER_EL7_OVA_S3
                    slackSend botUser: true, channel: '#pmm-qa', color: '#00FF00', message: "[${JOB_NAME}]: ${BUILD_URL} RC build finished, EL9 Image: " + env.PMM2_SERVER_OVA_S3 + " EL7 Image: " + env.PMM2_SERVER_EL7_OVA_S3
                }
                else
                {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished, EL9 Image: " + env.PMM2_SERVER_OVA_S3 + " EL7 Image: " + env.PMM2_SERVER_EL7_OVA_S3
                }
            }
        }
        failure {
            echo "Pipeline failed"
            slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build failed ${BUILD_URL}"
        }
        cleanup {
            deleteDir()
        }
    }
}

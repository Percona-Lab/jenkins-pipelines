pipeline {
    agent {
        label 'ovf-do-el9'
    }
    parameters {
        string(
            defaultValue: 'v3',
            description: 'Tag/Branch for pmm repository',
            name: 'PMM_BRANCH')
        string(
            defaultValue: 'docker.io/percona/pmm-server:3-dev-latest',
            description: 'Docker image for PMM Server running in the AMI',
            name: 'PMM_SERVER_IMAGE')
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
        upstream upstreamProjects: 'pmm3-server-autobuild', threshold: hudson.model.Result.SUCCESS
    }

    stages {
        stage('Prepare') {
            steps {
                script {
                    env.PMM_VERSION = '3-dev-latest'
                    if (params.RELEASE_CANDIDATE == 'yes') {
                        // release branch should be in the format: pmm-3.x.y
                        env.PMM_VERSION = PMM_BRANCH.split('-')[1] 
                    }
                    if (params.PMM_BRANCH != 'v3') {
                        env.PMM_VERSION = '3-dev-' + PMM_BRANCH
                    }
                }
                withCredentials([string(credentialsId: 'f5415992-e274-45c2-9eb9-59f9e8b90f43', variable: 'DIGITALOCEAN_ACCESS_TOKEN')]) {
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
            }
        }

        stage('Build Image') {
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    dir('build') {
                        sh "PMM_SERVER_IMAGE=${PMM_SERVER_IMAGE}  make pmm-ovf"
                    }
                }
                sh 'ls */*/PMM3-Server-*.ova | cut -d "/" -f 2 > IMAGE'
            }
        }
        stage('Upload Release Candidate Image') {
            when {
                expression { params.RELEASE_CANDIDATE == "yes" }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        FILE=$(ls */*/PMM3-Server-*.ova)
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
                          s3://percona-vm/PMM3-Server-${PMM_VERSION}.ova
                    '''
                }
                script {
                    env.PMM3_SERVER_OVA_S3 = "https://percona-vm.s3.amazonaws.com/PMM3-Server-${PMM_VERSION}.ova"
                }
            }
        }
        stage('Upload Dev-Latest Image') {
            when {
                expression { params.RELEASE_CANDIDATE == "no" }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        FILE=$(ls */*/PMM3-Server-*.ova)
                        NAME=$(basename ${FILE})
                        aws s3 cp \
                          --only-show-errors \
                          --acl public-read \
                          ${FILE} \
                          s3://percona-vm/${NAME}

                        # This will redirect to the image above
                        echo /${NAME} > PMM3-Server-dev-latest.ova
                        aws s3 cp \
                          --only-show-errors \
                          --website-redirect /${NAME} \
                          PMM3-Server-dev-latest.ova \
                          s3://percona-vm/PMM3-Server-dev-latest.ova
                    '''
                }
                script {
                    env.PMM3_SERVER_OVA_S3 = "http://percona-vm.s3-website-us-east-1.amazonaws.com/PMM3-Server-dev-latest.ova"
                }
            }
        }
    }

    post {
        success {
            script {
                if (params.RELEASE_CANDIDATE == "yes") {
                    currentBuild.description = "RC Build, Image: " + env.PMM3_SERVER_OVA_S3
                    slackSend botUser: true, channel: '#pmm-qa', color: '#00FF00', message: "[${JOB_NAME}]: ${BUILD_URL} RC build finished, Image: " + env.PMM3_SERVER_OVA_S3
                } else {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished, Image: " + env.PMM3_SERVER_OVA_S3
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

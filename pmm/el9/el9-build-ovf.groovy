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
            description: "Build a Release Candidate?",
            name: 'RELEASE_CANDIDATE')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    stages {
        stage('Prepare') {
            steps {
                script {
                    env.PMM_VERSION = 'dev-latest-el9'
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
                // slackSend botUser: true,
                //           channel: '#pmm-ci',
                //           color: '#0000FF',
                //           message: "[${JOB_NAME}]: build started - ${BUILD_URL}"
                checkout([$class: 'GitSCM', 
                          branches: [[name: "*/${PMM_BRANCH}"]],
                          extensions: [[$class: 'CloneOption',
                          noTags: true,
                          reference: '',
                          shallow: true]],
                          userRemoteConfigs: [[url: 'https://github.com/percona/pmm.git']]])
                sh '''
                    mkdir -p build/update
                    # copy update playbook to `build` to not have to pull it from pmm-update
                    cp -rpav update/ansible/playbook/* build/update
                '''
                dir('build') {
                    sh '''
                        make fetch-el9
                    '''
                }
            }
        }

        stage('Build Release Candidate Image') {
            when {
                expression { params.RELEASE_CANDIDATE == "yes" }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    dir('build') {
                        sh '''
                            make pmm2-ovf-el9-rc
                        '''
                    }
                }
                sh 'ls */*/*.ova | cut -d "/" -f 2 > IMAGE'
                stash includes: 'IMAGE', name: 'IMAGE'
            }
        }
        stage('Build Dev-Latest Image') {
            when {
                expression { params.RELEASE_CANDIDATE == "no" }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    dir('build') {
                        sh '''
                            make pmm2-ovf-el9-dev-latest
                        '''
                    }
                }
                sh 'ls */*/*.ova | cut -d "/" -f 2 > IMAGE'
                stash includes: 'IMAGE', name: 'IMAGE'
            }
        }

        stage('Upload Release Candidate') {
            when {
                expression { params.RELEASE_CANDIDATE == "yes" }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        FILE=$(ls */*/*.ova)
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
                            s3://percona-vm/PMM2-Server-${PMM_VERSION}.el9.ova
                    '''
                }
            }
        }
        stage('Upload Dev-Latest') {
            when {
                expression { params.RELEASE_CANDIDATE == "no" }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh '''
                        FILE=$(ls */*/*.ova)
                        NAME=$(basename ${FILE})
                        aws s3 cp \
                            --only-show-errors \
                            --acl public-read \
                            ${FILE} \
                            s3://percona-vm/${NAME}

                        # This will redirect to the image above
                        echo /${NAME} > PMM2-Server-dev-latest.el9.ova

                        aws s3 cp \
                            --only-show-errors \
                            --website-redirect /${NAME} \
                            PMM2-Server-dev-latest.el9.ova \
                            s3://percona-vm/PMM2-Server-dev-latest.el9.ova
                    '''
                }
            }
        }
    }

    post {
        success {
            script {
                unstash 'IMAGE'
                def IMAGE = sh(returnStdout: true, script: "cat IMAGE").trim()
                if (params.RELEASE_CANDIDATE == "yes"){
                    currentBuild.description = "OL9 RC Build, Image: " + IMAGE
                    // slackSend botUser: true, channel: '#pmm-qa', color: '#00FF00', message: "[${JOB_NAME}]: ${BUILD_URL} OL9 RC build finished - http://percona-vm.s3.amazonaws.com/PMM2-Server-${PMM_VERSION}.ova"
                } else {
                    slackSend botUser: true, channel: '@alexander.tymchuk', color: '#00FF00', message: "[${JOB_NAME}]: build finished - http://percona-vm.s3.amazonaws.com/${IMAGE}"
                    // slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - http://percona-vm.s3.amazonaws.com/${IMAGE}"
                }
            }
        }
        failure {
            script {
                sh '''
                    cat build.log
                '''
                // slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build failed ${BUILD_URL}"
            }
        }
        cleanup {
            deleteDir()
        }
    }
}

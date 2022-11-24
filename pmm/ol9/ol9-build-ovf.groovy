def pmmVersion = 'dev-latest'
if (RELEASE_CANDIDATE == 'yes') {
    pmmVersion = PMM_BRANCH.split('-')[1] //release branch should be in format: pmm-2.x.y
}

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
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    triggers {
        upstream upstreamProjects: 'ol9-build-server', threshold: hudson.model.Result.SUCCESS
    }
    stages {
        stage('Prepare') {
            steps {
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
                dir('build') {
                    sh '''
                        make fetch
                    '''
                }
            }
        }

        stage('Build Image Release Candidate') {
            when {
                expression { env.RELEASE_CANDIDATE == "yes" }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    dir("build") {
                        sh '''
                            /usr/bin/packer build \
                            -var 'pmm_client_repos=original testing' \
                            -var 'pmm_client_repo_name=percona-testing-x86_64' \
                            -var 'pmm2_server_repo=testing' \
                            -only virtualbox-ovf -color=false packer/pmm2.el9.json \
                                | tee build.log
                        '''
                    }
                }
                sh 'ls */*/*.ova | cut -d "/" -f 2 > IMAGE'
                stash includes: 'IMAGE', name: 'IMAGE'
                archiveArtifacts 'IMAGE'
            }
        }
        stage('Build Image Dev-Latest') {
            when {
                expression { env.RELEASE_CANDIDATE == "no" }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    dir("build") {
                        sh '''
                            /usr/bin/packer build \
                            -var 'pmm_client_repos=original experimental' \
                            -var 'pmm_client_repo_name=percona-experimental-x86_64' \
                            -var 'pmm2_server_repo=experimental' \
                            -only virtualbox-ovf -color=false packer/pmm2.json \
                                | tee build.log
                        '''
                    }
                }
                sh 'ls */*/*.ova | cut -d "/" -f 2 > IMAGE'
                stash includes: 'IMAGE', name: 'IMAGE'
                archiveArtifacts 'IMAGE'
            }
        }

        stage('Upload Release Candidate') {
            when {
                expression { env.RELEASE_CANDIDATE == "yes" }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    withEnv(['pmmVersion=' + pmmVersion]) {
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
                                s3://percona-vm/PMM2-Server-${pmmVersion}.ova
                        '''
                    }
                }
            }
        }
        stage('Upload Dev-Latest') {
            when {
                expression { env.RELEASE_CANDIDATE == "no" }
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

                        # Investigate, seems like a failover for whoever relies on this filename
                        echo /${NAME} > PMM2-Server-dev-latest.ova

                        aws s3 cp \
                            --only-show-errors \
                            --website-redirect /${NAME} \
                            PMM2-Server-dev-latest.ova \
                            s3://percona-vm/PMM2-Server-dev-latest.ova
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
                    // slackSend botUser: true, channel: '#pmm-qa', color: '#00FF00', message: "[${JOB_NAME}]: ${BUILD_URL} OL9 RC build finished - http://percona-vm.s3.amazonaws.com/PMM2-Server-${pmmVersion}.ova"
                } else {
                    slackSend botUser: true, channel: '@alexander.tymchuk', color: '#00FF00', message: "[${JOB_NAME}]: build finished - http://percona-vm.s3.amazonaws.com/${IMAGE}"
                    // slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${JOB_NAME}]: build finished - http://percona-vm.s3.amazonaws.com/${IMAGE}"
                }
            }
        }
        // failure {
        //     slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${JOB_NAME}]: build failed ${BUILD_URL}"
        // }
        cleanup {
            deleteDir()
        }
    }
}

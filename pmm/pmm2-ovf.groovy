def pmmVersion = 'dev-latest'
if (RELEASE_CANDIDATE == 'yes') {
    pmmVersion = PMM_BRANCH.split('-')[1] //release branch should be in format: pmm-2.x.y
}


pipeline {
    environment {
        specName = 'OVF'
    }
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
        upstream upstreamProjects: 'pmm2-server-autobuild', threshold: hudson.model.Result.SUCCESS
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
                slackSend botUser: true,
                          channel: '#pmm-ci',
                          color: '#0000FF',
                          message: "[${specName}]: build started - ${BUILD_URL}"
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
                    sh """
                        FILE=\$(ls */*/*.ova)
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
                            s3://percona-vm/PMM2-Server-${pmmVersion}.ova
                    """
                }
            }
        }
        stage('Upload Dev-Latest') {
            when {
                expression { env.RELEASE_CANDIDATE == "no" }
            }
            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: 'pmm-staging-slave', secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']]) {
                    sh """
                        FILE=\$(ls */*/*.ova)
                        NAME=\$(basename \${FILE})
                        aws s3 cp \
                            --only-show-errors \
                            --acl public-read \
                            \${FILE} \
                            s3://percona-vm/\${NAME}

                        echo /\${NAME} > PMM2-Server-dev-latest.ova
                        aws s3 cp \
                            --only-show-errors \
                            --website-redirect /\${NAME} \
                            PMM2-Server-dev-latest.ova \
                            s3://percona-vm/PMM2-Server-dev-latest.ova
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
            script {
                unstash 'IMAGE'
                def IMAGE = sh(returnStdout: true, script: "cat IMAGE").trim()
                if ("${RELEASE_CANDIDATE}" == "yes")
                {
                    currentBuild.description = "Release Candidate Build"
                    slackSend botUser: true, channel: '#pmm-qa', color: '#00FF00', message: "[${specName}]: ${BUILD_URL} Release Candidate build finished - http://percona-vm.s3.amazonaws.com/PMM2-Server-${pmmVersion}.ova"
                }
                else
                {
                    slackSend botUser: true, channel: '#pmm-ci', color: '#00FF00', message: "[${specName}]: build finished - http://percona-vm.s3.amazonaws.com/${IMAGE}"
                }
            }
        }
        failure {
            slackSend botUser: true, channel: '#pmm-ci', color: '#FF0000', message: "[${specName}]: build failed"
        }
    }
}

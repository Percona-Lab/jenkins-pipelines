library changelog: false, identifier: 'lib@PMM-6352-custom-build-el9', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: 'PMM-6352-custom-build-el9', // TODO: set to 'PMM-2.0'
            description: 'Choose a pmm-submodules branch to build the RC from',
            name: 'RELEASE_BRANCH')
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        skipDefaultCheckout()
        skipStagesAfterUnstable()
        disableConcurrentBuilds()
        parallelsAlwaysFailFast()
    }
    environment {
        // TODO: remove once tested, it's intentionally hard-coded
        REMOVE_RELEASE_BRANCH = 'no'
        PATH_TO_SCRIPTS = 'sources/pmm/src/github.com/percona/pmm/build/scripts'
    }
    stages {
        stage('Get version') {
            steps {
                deleteDir()
                script {
                    git branch: params.RELEASE_BRANCH,
                        credentialsId: 'GitHub SSH Key',
                        poll: false,
                        url: 'git@github.com:Percona-Lab/pmm-submodules'
                    env.VERSION = sh(returnStdout: true, script: "cat VERSION").trim()
                }
            }
        }
        stage('Check if Release Branch Exists') {
            steps {
                script {
                    currentBuild.description = env.VERSION
                    slackSend botUser: true,
                        channel: '@alexander.tymchuk',
                        color: '#0000FF',
                        message: "OL9 build for PMM ${VERSION} has started. You can check progress at: ${BUILD_URL}"
                    env.EXIST = sh(
                        script: 'git ls-remote --heads https://github.com/Percona-Lab/pmm-submodules ${RELEASE_BRANCH} | wc -l',
                        returnStdout: true
                    ).trim()
                }
            }
        }
        stage('Checkout Submodules and Prepare for creating branches') {
            when {
                expression { env.EXIST.toInteger() == 0 }
            }
            steps {
                error "The branch does not exist. Please create a branch in Percona-Lab/pmm-submodules"
            }
        }
        stage('Rewind Release Submodules') {
            when {
                expression { env.REMOVE_RELEASE_BRANCH == "no" }
            }
            steps {
                script {
                    sh '''
                        sudo wget https://github.com/mikefarah/yq/releases/latest/download/yq_linux_arm64 -O /usr/bin/yq
                        sudo chmod +x /usr/bin/yq
                    '''
                    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
                        sh '''
                            git config --global user.email "noreply@percona.com"
                            git config --global user.name "PMM Jenkins"                            
                            git config -f .gitmodules submodule.percona-toolkit.shallow false
                            git config remote.origin.fetch "+refs/heads/*:/refs/remotes/origin/*"
                            git config push.default "current"

                            git submodule update --init --remote --recommend-shallow --jobs 10
                            git submodule status | grep "^\\+" | sed -e "s/\\+//" | cut -d " " -f2 > remotes.txt

                            cat remotes.txt
                            yq ea 'select(fileIndex == 0) *d select(fileIndex == 1) | .deps' ci-default.yml ci.yml > branches.yml

                            COUNT=0
                            for SUBMODULE in $(cat remotes.txt); do
                                cd $SUBMODULE
                                git fetch
                                git remote update

                                CURRENT_BRANCH=$(git symbolic-ref --short HEAD 2>/dev/null || git rev-parse HEAD)
                                # REMOTE_BRANCHES=$(git ls-remote --heads origin | awk -F '/' '{print $NF}')

                                BRANCH=$(cat ${OLDPWD}/branches.yml | yq '.[] | select(.path == env(SUBMODULE)) | .branch')
                                if [ -n $BRANCH ]; then
                                    git checkout $BRANCH
                                else
                                    echo "Error: no branch found for ${SUBMODULE} in ci.yml"
                                    exit 1
                                fi
                                
                                LOCAL=$(git rev-parse @)
                                BASE=$(git merge-base @ @{u} 2>/dev/null)
                                REMOTE=$(git rev-parse @{u})

                                if [ $LOCAL = $BASE ]; then
                                    git pull origin
                                    git log --oneline -n 3
                                    cd -
                                    git add $SUBMODULE
                                    git status --short
                                    ((COUNT+=1))
                                else
                                    cd -
                                    echo "${SUBMODULE} is up-to-date with upstream"
                                fi
                            done

                            rm -f remotes.txt branches.yml
                            git submodule --quiet summary

                            if [ $COUNT -gt 0 ]; then
                                git commit -m "rewind submodules"
                                git push
                            fi

                            # run the build script
                            # ${PATH_TO_SCRIPTS}/build-submodules
                        '''
                    }
                }
            }
        }
        // stage('Build Server & Client') {
        //     when {
        //         expression { env.REMOVE_RELEASE_BRANCH == "no" }
        //     }
        //     parallel {
        //         stage('Start OL9 Server Build') {
        //             steps {
        //                 script {
        //                     build job: 'el9-build-server', parameters: [
        //                         string(name: 'GIT_BRANCH', value: RELEASE_BRANCH),
        //                         string(name: 'DESTINATION', value: 'testing') // TODO: revert to the original value
        //                     ]
        //                 }
        //             }
        //         }
        //         stage('Start OL9 Client Build') {
        //             steps {
        //                 script {
        //                     pmm2Client = build job: 'el9-build-client', parameters: [
        //                         string(name: 'GIT_BRANCH', value: RELEASE_BRANCH),
        //                         string(name: 'DESTINATION', value: 'testing')
        //                     ]
        //                     env.TARBALL_URL = pmm2Client.buildVariables.TARBALL_URL                        
        //                 }
        //             }
        //         }
        //     }
        // }
        stage('Build OVF') {
            when {
                expression { env.REMOVE_RELEASE_BRANCH == "no" }
            }
            steps {
                script {
                    build job: 'el9-build-ovf', parameters: [
                        // TODO: get the branch from pmm-submodules' cy.yml
                        string(name: 'PMM_BRANCH', value: params.RELEASE_BRANCH), 
                        string(name: 'RELEASE_CANDIDATE', value: 'yes')
                    ]                    
                }
            }
        }
        // stage('Build AMI') {
        //     when {
        //         expression { env.REMOVE_RELEASE_BRANCH == "no" }
        //     }
        //     steps {
        //         script {
        //             pmm2AMI = build job: 'el9-build-ami', parameters: [
        //                 // TODO: get the branch from pmm-submodules' cy.yml
        //                 string(name: 'PMM_BRANCH', value: params.RELEASE_BRANCH),
        //                 string(name: 'RELEASE_CANDIDATE', value: 'yes')
        //             ]
        //             env.AMI_ID = pmm2AMI.buildVariables.AMI_ID
        //         }
        //     }
        // }
    }
    post {
        success {
            slackSend botUser: true,
                      channel: '@alexander.tymchuk',
                      color: '#00FF00',
                      message: """New RHEL9 RC is out :rocket:
Server: perconalab/pmm-server:${VERSION}-rc-el9
Client: perconalab/pmm-client:${VERSION}-rc-el9
OVA: https://percona-vm.s3.amazonaws.com/PMM2-Server-${VERSION}.el9.ova
AMI: ${env.AMI_ID}
Tarball: ${env.TARBALL_URL}
                      """
        }
        cleanup {
            deleteDir()
        }
    }
}

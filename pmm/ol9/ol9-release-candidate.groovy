library changelog: false, identifier: 'lib@PMM-6352-custom-build-ol9', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void runPMM2AMIBuild(String SUBMODULES_GIT_BRANCH, String RELEASE_CANDIDATE) {
    pmm2AMI = build job: 'pmm2-ami', parameters: [
        string(name: 'PMM_BRANCH', value: SUBMODULES_GIT_BRANCH),
        string(name: 'RELEASE_CANDIDATE', value: RELEASE_CANDIDATE)
    ]
    env.AMI_ID = pmm2AMI.buildVariables.AMI_ID
}

// String DEFAULT_BRANCH = 'PMM-2.0'
String DEFAULT_BRANCH = 'PMM-6352-custom-build-ol9'

pipeline {
    agent {
        label 'cli'
    }
    parameters {
        string(
            defaultValue: DEFAULT_BRANCH,
            description: 'Choose a pmm-submodules branch to build the image from',
            name: 'SUBMODULES_GIT_BRANCH')
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '30'))
        skipDefaultCheckout()
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
                    git branch: env.SUBMODULES_GIT_BRANCH,
                        credentialsId: 'GitHub SSH Key',
                        poll: false,
                        url: 'git@github.com:Percona-Lab/pmm-submodules'
                    env.VERSION = sh(returnStdout: true, script: "cat VERSION").trim()
                    env.RELEASE_BRANCH = DEFAULT_BRANCH
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
                    // build job: 'pmm2-rewind-submodules-fb', propagate: false, parameters: [
                    //     string(name: 'GIT_BRANCH', value: SUBMODULES_GIT_BRANCH)
                    // ]
                    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
                        sh '''
                            git config --global user.email "noreply@percona.com"
                            git config --global user.name "PMM Jenkins"                            
                            git config -f .gitmodules submodule.grafana.shallow true
                            git config -f .gitmodules submodule.grafana-dashboards.shallow true
                            git config -f .gitmodules submodule.pmm-qa.shallow true
                            # git config -f .gitmodules submodule.percona-toolkit.shallow true
                            git config remote.origin.fetch "+refs/heads/*:refs/remotes/origin/*"
                            git config push.default "current"

                            git submodule update --init --remote --jobs 10
                            git submodule status | grep "^\\+" | sed -e "s/\\+//" | cut -d " " -f2 > remotes.txt
                            cat remotes.txt

                            COUNT=0
                            for sub in $(cat remotes.txt); do
                                cd $sub
                                git fetch
                                git remote update

                                if [ "$sub" = "sources/pmm/src/github.com/percona/pmm" ]; then 
                                    git checkout PMM-6352-custom-build-el9
                                else
                                    # we assume tho remote base branch is `main`
                                    git checkout main 
                                fi
                                
                                LOCAL=$(git rev-parse @)
                                BASE=$(git merge-base @ @{u} 2>/dev/null)
                                REMOTE=$(git rev-parse @{u})

                                if [ $LOCAL = $BASE ]; then
                                    git pull origin
                                    git log --oneline -n 3
                                    cd -
                                    git add $sub
                                    git status --short
                                    ((COUNT+=1))
                                else
                                    cd -
                                    echo "${sub} is up-to-date with upstream"
                                fi
                            done

                            if [ $COUNT -gt 0 ]; then
                                git commit -m "rewind submodules"
                                # git push
                            fi

                            # check if changes are present
                            # ls -la ${PATH_TO_SCRIPTS}
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
        //                     build job: 'ol9-build-server', parameters: [
        //                         string(name: 'GIT_BRANCH', value: RELEASE_BRANCH),
        //                         string(name: 'DESTINATION', value: 'testing') // TODO: revert to the original value
        //                     ]
        //                 }
        //             }
        //         }
        //         stage('Start OL9 Client Build') {
        //             steps {
        //                 script {
        //                     pmm2Client = build job: 'ol9-build-client', parameters: [
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
                    build job: 'ol9-build-ovf', parameters: [
                        // TODO: get the branch from pmm-submodules' cy.yml
                        string(name: 'PMM_BRANCH', value: 'PMM-6352-custom-build-el9'), 
                        string(name: 'RELEASE_CANDIDATE', value: 'yes')
                    ]                    
                }
            }
        }
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

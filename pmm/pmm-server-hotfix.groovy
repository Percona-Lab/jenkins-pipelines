def userInput = ''
pipeline {
    environment {
        specName = 'pmm-hotfix'
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
            defaultValue: '1.5.3',
            description: 'PMM Server version',
            name: 'VERSION')
    }
    stages {
        stage('Get Docker RPMs') {
            agent {
                label 'docker'
            }
            steps {
                slackSend channel: '#pmm-ci', color: '#FFFF00', message: "[${specName}]: build started - ${env.BUILD_URL}"
                sh "docker run ${DOCKER_VERSION} /usr/bin/rpm -qa | tee rpms.list"
            }
        }
        stage('Get repo RPMs') {
            steps {
                sh '''
                    ssh -i ~/.ssh/id_rsa uploader@repo.ci.percona.com \
                        ls /srv/repo-copy/laboratory/7/RPMS/x86_64 \
                        | tee repo.list
                '''
                stash includes: 'repo.list', name: 'repo'
            }
        }
        stage('Wait input') {
            steps {
                script {
                    unstash 'repo'
                    RPMList = sh returnStdout: true, script: 'cat repo.list'
                    timeout(time:10, unit:'MINUTES') {
                        userInput = input message: 'What RPM do you want to release?', parameters: [string(defaultValue: '', description: '', name: 'filename')]
                        if ( !RPMList.toLowerCase().contains(userInput.toLowerCase())) {
                            echo  'Unknown RPM'
                            error 'Unknown RPM'
                        }
                    }
                }
                sh """
                    grep "^${userInput}" repo.list \
                        | tee copy.list

                    if [ ! -s copy.list ]; then
                        echo Cannot find ${userInput} rpm...
                        exit 1
                    fi

                    if [ "x\$(cat copy.list | wc -l)" != "x1" ]; then
                        echo Too many rpms mached
                        exit 1
                    fi
                """
                stash includes: 'copy.list', name: 'copy'
                archiveArtifacts 'copy.list'
            }
        }
        stage('Copy RPMs to PMM repo') {
            steps {
                unstash 'copy'
                sh '''
                    cat copy.list | ssh -i ~/.ssh/id_rsa uploader@repo.ci.percona.com \
                        "cat - | xargs -I{} cp -v /srv/repo-copy/laboratory/7/RPMS/x86_64/{} /srv/repo-copy/pmm/7/RPMS/x86_64/{}"
                '''
            }
        }
        stage('Createrepo') {
            steps {
                sh '''
                    ssh -i ~/.ssh/id_rsa uploader@repo.ci.percona.com \
                        createrepo --update /srv/repo-copy/pmm/7/RPMS/x86_64/
                '''
            }
        }
        stage('Publish RPMs') {
            steps {
                build job: 'sync-repos-to-production', parameters: [booleanParam(name: 'REVERSE', value: false)]
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
                                    git clone git@github.com:${repo["$package"]} ./
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
    }

    post {
        success {
            script {
                def IMAGE = sh(returnStdout: true, script: "cat copy.list").trim()
                slackSend channel: '#pmm-ci', color: '#00FF00', message: "[${specName}]: build finished - ${IMAGE}"
                deleteDir()
            }
        }
        failure {
            slackSend channel: '#pmm-ci', color: '#FF0000', message: "[${specName}]: build failed"
            deleteDir()
        }
    }
}

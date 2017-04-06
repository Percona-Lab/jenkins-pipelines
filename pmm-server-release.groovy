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
            description: '',
            name: 'DOCKER_VERSION')
        string(
            defaultValue: '',
            description: '',
            name: 'AMI_VERSION')
        string(
            defaultValue: '',
            description: '',
            name: 'OVF_VERSION')
        string(
            defaultValue: '1.1.3',
            description: '',
            name: 'VERSION')
    }
    stages {
        stage('Get Docker RPMs') {
            agent {
                label 'docker'
            }
            steps {
                slackSend channel: '#pmm-jenkins', color: '#FFFF00', message: "[${specName}]: build started - ${env.BUILD_URL}"
                sh "docker run ${DOCKER_VERSION} /usr/bin/rpm -qa > rpms.list"
                stash includes: 'rpms.list', name: 'rpms'
            }
        }
        stage('Get repo RPMs') {
            steps {
                unstash 'rpms'
                sh '''
                    ssh -i ~/.ssh/percona-jenkins-slave-access uploader@repo.ci.percona.com \
                        ls /srv/repo-copy/laboratory/7/RPMS/x86_64 \
                        > repo.list
                    cat rpms.list \
                        | sed -e 's/[^A-Za-z0-9\\._+-]//g' \
                        | xargs -n 1 -I {} grep "^{}.rpm" repo.list \
                        | tee copy.list
                '''
                stash includes: 'copy.list', name: 'copy'
                archiveArtifacts 'copy.list'
            }
        }
        stage('Copy RPMs to PMM repo') {
            steps {
                unstash 'copy'
                sh '''
                    cat copy.list | ssh -i ~/.ssh/percona-jenkins-slave-access uploader@repo.ci.percona.com \
                        "cat - | xargs -I{} cp -v /srv/repo-copy/laboratory/7/RPMS/x86_64/{} /srv/repo-copy/pmm/7/RPMS/x86_64/{}"
                '''
            }
        }
        stage('Createrepo') {
            steps {
                sh '''
                    ssh -i ~/.ssh/percona-jenkins-slave-access uploader@repo.ci.percona.com \
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
                            ["pmm-update"]="Percona-Lab/pmm-update"
                            ["pmm-manage"]="Percona-Lab/pmm-manage"
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
        stage('Set Docker Tag') {
            agent {
                label 'docker'
            }
            steps {
                sh """
                    docker pull ${DOCKER_VERSION}
                    docker tag ${DOCKER_VERSION} percona/pmm-server:${VERSION}
                    docker tag ${DOCKER_VERSION} percona/pmm-server:latest
                    docker push percona/pmm-server:${VERSION}
                    docker push percona/pmm-server:latest
                """
            }
        }
        stage('Publish OVF') {
            agent {
                label 'master'
            }
            steps {
                sh """
                    ssh -i ~/.ssh/id_rsa_downloads jenkins@10.10.9.216 "
                        cd /data/downloads/TESTING/pmm
                        wget https://s3.amazonaws.com/percona-vm/${OVF_VERSION}
                    "
                """
            }
        }
    }
    
    post {
        success {
            script {
                def IMAGE = sh(returnStdout: true, script: "cat copy.list").trim()
                slackSend channel: '#pmm-jenkins', color: '#00FF00', message: "[${specName}]: build finished - ${IMAGE}"
            }
        }
        failure {
            slackSend channel: '#pmm-jenkins', color: '#FF0000', message: "[${specName}]: build failed"
        }
        always {
            deleteDir()
        }
    }
}

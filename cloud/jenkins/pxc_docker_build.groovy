void generateImageSummary(filePath) {
    def images = readFile(filePath).trim().split("\n")

    def report = "<h2>Image Summary Report</h2>\n"
    report += "<p><strong>Total Images:</strong> ${images.size()}</p>\n"
    report += "<ul>\n"

    images.each { image ->
        report += "<li>${image}</li>\n"
    }

    report += "</ul>\n"
    return report
}

String getTrivyCveSummary(reportPath, imageName) {
    if (!fileExists(reportPath)) {
        return ''
    }

    def report = readFile(reportPath)
    int highCount = report.split('\\[HIGH\\]', -1).size() - 1
    int criticalCount = report.split('\\[CRITICAL\\]', -1).size() - 1

    if (highCount == 0 && criticalCount == 0) {
        return ''
    }

    return "\n*CVEs found:*\n*${imageName}*\n*CRITICAL* `${criticalCount}` *HIGH* `${highCount}`\n"
}

pipeline {
    parameters {
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-xtradb-cluster-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-xtradb-cluster-operator',
            description: 'percona-xtradb-cluster-operator repository',
            name: 'GIT_REPO')
    }
    agent {
         label 'docker-x64-min'
    }
    environment {
        PATH = "${WORKSPACE}/node_modules/.bin:$PATH" // Add local npm bin to PATH
        DOCKER_REPOSITORY_PASSPHRASE = credentials('DOCKER_REPOSITORY_PASSPHRASE')
        DOCKER_TAG = sh(script: "echo ${GIT_BRANCH} | sed -e 's^/^-^g; s^[.]^-^g;' | tr '[:upper:]' '[:lower:]'", returnStdout: true).trim()
        TRIVY_VERSION = '0.69.3'
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }

    stages {
        stage('Prepare') {
            steps {
                git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'
                sh """
                    wget https://github.com/aquasecurity/trivy/releases/download/v\${TRIVY_VERSION}/trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz
                    sudo tar zxvf trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz -C /usr/local/bin/
                    rm -f trivy_\${TRIVY_VERSION}_Linux-64bit.tar.gz

                    if [ ! -f /tmp/junit.tpl ]; then
                        wget --directory-prefix=/tmp https://raw.githubusercontent.com/aquasecurity/trivy/v\${TRIVY_VERSION}/contrib/junit.tpl
                    fi

                    export GIT_REPO=\$(echo \${GIT_REPO} | sed "s#github.com#\${GITHUB_TOKEN}@github.com#g")

                    # sudo is needed for better node recovery after compilation failure
                    # if building failed on compilation stage directory will have files owned by docker user
                    sudo git config --global --add safe.directory '*'
                    sudo git reset --hard
                    sudo git clean -xdf
                    sudo rm -rf source
                    ./cloud/local/checkout $GIT_REPO $GIT_BRANCH
                """
                stash includes: "source/**", name: "sourceFILES"
            }
        }

        stage('Build and push docker image') {
            steps {
                retry(3) {
                    timeout(time: 30, unit: 'MINUTES') {
                        unstash "sourceFILES"
                        withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                            sh """
                                echo "\$PASS" | docker login -u "\$USER" --password-stdin
                                docker buildx create --use
                                TAG_PREFIX=\$(echo $GIT_BRANCH | sed 's^/^-^g; s^[.]^-^g;' | tr '[:upper:]' '[:lower:]')
                                IMAGE_NAME="percona-xtradb-cluster-operator"
                                cd ./source/
                                DOCKER_DEFAULT_PLATFORM='linux/amd64,linux/arm64' ./e2e-tests/build
                                docker logout
                                echo "perconalab/\$IMAGE_NAME:\${TAG_PREFIX}" >> list-of-images.txt
                            """
                       }
                    }
                }
            }
        }

        stage('Check PXC docker image') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'hub.docker.com', passwordVariable: 'PASS', usernameVariable: 'USER')]) {
                    sh """
                        IMAGE_NAME='percona-xtradb-cluster-operator'
                        TrivyLog="$WORKSPACE/trivy-hight-pxc.xml"
                        IMAGE_ID="\${IMAGE_NAME}-\${DOCKER_TAG}"

                        sg docker -c "
                            echo "\$PASS" | docker login -u "\$USER" --password-stdin
                            /usr/local/bin/trivy -q --cache-dir /mnt/jenkins/trivy-${JOB_NAME}/ image --format template --template @/tmp/junit.tpl -o \$TrivyLog --timeout 5m0s --ignore-unfixed --exit-code 0 --severity HIGH,CRITICAL perconalab/\$IMAGE_NAME:\${DOCKER_TAG}
                            docker logout
                        "
                        perl -pi -e 's/<testcase classname="/<testcase classname="'"\$IMAGE_ID"' :: /g; s/<testcase name="/<testcase name="'"\$IMAGE_ID"' :: /g' "\$TrivyLog"

                    """
                }
            }
            post {
                always {
                    junit allowEmptyResults: true, skipPublishingChecks: true, testResults: "trivy-hight-pxc.xml"
                    archiveArtifacts artifacts: "trivy-hight-pxc.xml", allowEmptyArchive: true
                }
            }
        }
    }

    post {
        always {
            script {
                if (fileExists('./source/list-of-images.txt')) {
                    def summary = generateImageSummary('./source/list-of-images.txt')

                    addSummary(icon: 'symbol-aperture-outline plugin-ionicons-api',
                        text: "<pre>${summary}</pre>"
                    )
                    // Also save as a file if needed
                     writeFile(file: 'image-summary.html', text: summary)
                } else {
                    echo 'No ./source/list-of-images.txt file found - skipping summary generation'
                }
            }
        }
        unstable {
            script {
                def trivySummary = getTrivyCveSummary('trivy-hight-pxc.xml', "perconalab/percona-xtradb-cluster-operator:${DOCKER_TAG}")
                slackSend channel: '#cloud-dev-ci', color: '#F6F930', message: "Building of *PXC* operator docker images unstable.${trivySummary} Please check the log ${BUILD_URL}"
            }
        }
        failure {
            script {
                def trivySummary = getTrivyCveSummary('trivy-hight-pxc.xml', "perconalab/percona-xtradb-cluster-operator:${DOCKER_TAG}")
                slackSend channel: '#cloud-dev-ci', color: '#FF0000', message: "Building of *PXC* operator docker image failed.${trivySummary} Please check the log ${BUILD_URL}"
            }
        }
        cleanup {
            sh '''
                sudo docker rmi -f \$(sudo docker images -q) || true
                sudo rm -rf ./source/build
            '''
            deleteDir()
        }
    }
}

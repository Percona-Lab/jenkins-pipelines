library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'TOKEN')]) {
        withCredentials([usernamePassword(credentialsId: 'PS_PRIVATE_REPO_ACCESS', passwordVariable: 'PASSWORD', usernameVariable: 'USERNAME')]) {
            sh """
                set -o xtrace
                mkdir -p test
                if [ \${FIPSMODE} = "YES" ]; then
                    PXC_VERSION_MINOR=\$(curl -s -O \$(echo \${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/\${GIT_BRANCH}/MYSQL_VERSION && cat MYSQL_VERSION | grep MYSQL_VERSION_MINOR | awk -F= '{print \$2}')
                    if [ \${PXC_VERSION_MINOR} = "0" ]; then
                        PRO_BRANCH="8.0"
                    elif [ \${PXC_VERSION_MINOR} = "4" ]; then
                        PRO_BRANCH="8.4"
                    else
                        PRO_BRANCH="trunk"
                    fi
                    curl -L -H "Authorization: Bearer \${TOKEN}" \
                        -H "Accept: application/vnd.github.v3.raw" \
                        -o pxc_builder.sh \
                        "https://api.github.com/repos/percona/percona-xtradb-cluster-private-build/contents/build-ps/pxc_builder.sh?ref=\${PRO_BRANCH}"
                    sed -i "s/PRIVATE_USERNAME/\${USERNAME}/g" pxc_builder.sh
                    sed -i "s/PRIVATE_TOKEN/\${PASSWORD}/g" pxc_builder.sh
                else
                    wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${GIT_BRANCH}/build-ps/pxc_builder.sh -O pxc_builder.sh
                fi
                pwd -P
                export build_dir=\$(pwd -P)
                docker run --shm-size=16g --cap-add=SYS_NICE -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
                    set -o xtrace
                    cd \${build_dir}
                    bash -x ./pxc_builder.sh --builddir=\${build_dir}/test --install_deps=1
                    if [ \${FIPSMODE} = "YES" ]; then
                        git clone --depth 1 --branch \${PRO_BRANCH} https://x-access-token:${TOKEN}@github.com/percona/percona-xtradb-cluster-private-build.git percona-xtradb-cluster-private-build
                        mv -f \${build_dir}/percona-xtradb-cluster-private-build/build-ps \${build_dir}/test/.
                    fi
                    bash -x ./pxc_builder.sh --builddir=\${build_dir}/test --repo=${GIT_REPO} --branch=${GIT_BRANCH} --rpm_release=${RPM_RELEASE} --deb_release=${DEB_RELEASE} --bin_release=${BIN_RELEASE} ${STAGE_PARAM}"
            """
        }
    }
}

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

def AWS_STASH_PATH

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
    }
    parameters {
        choice(
             choices: [ 'Hetzner','AWS' ],
             description: 'Cloud infra for build',
             name: 'CLOUD' )
        string(
            defaultValue: 'https://github.com/percona/percona-xtradb-cluster.git',
            description: 'URL for percona-xtradb-cluster repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '8.0',
            description: 'Tag/Branch for percona-xtradb-cluster repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: '1',
            description: 'RPM release value',
            name: 'RPM_RELEASE')
        string(
            defaultValue: '1',
            description: 'DEB release value',
            name: 'DEB_RELEASE')
        string(
            defaultValue: '1',
            description: 'BIN release value',
            name: 'BIN_RELEASE')
        booleanParam(
            defaultValue: false,
            description: "Skips packages for OL10",
            name: 'SKIP_OL10'
        )
        booleanParam(
            defaultValue: false,
            description: "Skips packages for Debian 13",
            name: 'SKIP_TRIXIE'
        )
        choice(
            choices: 'NO\nYES',
            description: 'Enable fipsmode',
            name: 'FIPSMODE')
        choice(
            choices: 'testing\nexperimental\nlaboratory',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
        choice(
            choices: '#releases-ci\n#releases',
            description: 'Channel for notifications',
            name: 'SLACKNOTIFY')
        string(
            defaultValue: '',
            description: 'Comma-separated list of build stages to run (e.g. "Oracle Linux 9,Oracle Linux 9 ARM"). Leave empty to run all stages.',
            name: 'BUILD_STAGES')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps ()
    }
    stages {
        stage('Create PXC source tarball') {
            agent {
               label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
            }
            steps {
                slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: starting build for ${GIT_BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
                script {
                    if (env.FIPSMODE == 'YES') {
                        buildStage("centos:7", "--get_sources=1 --enable_fipsmode=1")
                    } else {
                        buildStage("centos:7", "--get_sources=1")
                    }
                }
                sh '''
                   REPO_UPLOAD_PATH=$(grep "DEST=UPLOAD" test/pxc-80.properties | cut -d = -f 2 | sed "s:$:${BUILD_NUMBER}:")
                   AWS_STASH_PATH=$(echo ${REPO_UPLOAD_PATH} | sed  "s:UPLOAD/experimental/::")
                   echo ${REPO_UPLOAD_PATH} > uploadPath
                   echo ${AWS_STASH_PATH} > awsUploadPath
                   cat test/pxc-80.properties
                   cat uploadPath
                   cat awsUploadPath
                '''
                script {
                    AWS_STASH_PATH = sh(returnStdout: true, script: "cat awsUploadPath").trim()
                }
                script {
                    sh """
                        curl -s \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${GIT_BRANCH}/MYSQL_VERSION -o MYSQL_VERSION
                    """
                    env.MYSQL_VERSION_MINOR = sh(returnStdout: true, script: "grep '^MYSQL_VERSION_MINOR=' MYSQL_VERSION | cut -d= -f2").trim()
                    echo "Detected PXC version minor: ${env.MYSQL_VERSION_MINOR}"
                }
                stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                stash includes: 'uploadPath', name: 'uploadPath'
                pushArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                uploadTarballfromAWS(params.CLOUD, "source_tarball/", AWS_STASH_PATH, 'source')
            }
        }
        stage('Build PXC generic source packages') {
            parallel {
                stage('Build PXC generic source rpm') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("centos:7", "--build_src_rpm=1 --enable_fipsmode=1")
                            } else {
                                buildStage("centos:7", "--build_src_rpm=1")
                            }
                        }
                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder(params.CLOUD, "srpm/", AWS_STASH_PATH)
                        uploadRPMfromAWS(params.CLOUD, "srpm/", AWS_STASH_PATH)
                    }
                }
                stage('Build PXC generic source deb') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-32gb'
                    }
                    steps {
                        cleanUpWS()
                        unstash 'pxc-80.properties'
                        popArtifactFolder(params.CLOUD, "source_tarball/", AWS_STASH_PATH)
                        script {
                            if (env.FIPSMODE == 'YES') {
                                buildStage("ubuntu:xenial", "--build_source_deb=1 --enable_fipsmode=1")
                            } else {
                                buildStage("ubuntu:xenial", "--build_source_deb=1")
                            }
                        }
                        stash includes: 'test/pxc-80.properties', name: 'pxc-80.properties'
                        pushArtifactFolder(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                        uploadDEBfromAWS(params.CLOUD, "source_deb/", AWS_STASH_PATH)
                    }
                }
            }  //parallel
        } // stage
        stage('Build PXC RPMs/DEBs/Binary tarballs') {
            steps {
                script {
                    pxc80BuildMatrix(
                        cloud:        params.CLOUD,
                        awsStashPath: AWS_STASH_PATH,
                        fipsMode:     env.FIPSMODE,
                        skipOL10:     env.SKIP_OL10.toBoolean(),
                        skipTrixie:   env.SKIP_TRIXIE.toBoolean(),
                        versionMinor: env.MYSQL_VERSION_MINOR,
                        onlyStages:   params.BUILD_STAGES ? params.BUILD_STAGES.split(',').collect { it.trim() } : []
                    )
                }
            }
        }

        stage('Sign packages') {
            steps {
                script {
                    def rpmStages = [
                        'Centos 8', 'Centos 8 ARM', 'Oracle Linux 9', 'Oracle Linux 9 ARM',
                        'Oracle Linux 10', 'Oracle Linux 10 ARM', 'Amazon Linux 2023', 'Amazon Linux 2023 ARM',
                        'Centos 8 tarball', 'Oracle Linux 9 tarball', 'Debian Bullseye(11) tarball'
                    ]
                    def debStages = [
                        'Ubuntu Jammy(22.04)', 'Ubuntu Jammy(22.04) ARM',
                        'Ubuntu Noble(24.04)', 'Ubuntu Noble(24.04) ARM',
                        'Ubuntu Resolute(26.04)', 'Ubuntu Resolute(26.04) ARM',
                        'Debian Bullseye(11)', 'Debian Bullseye(11) ARM',
                        'Debian Bookworm(12)', 'Debian Bookworm(12) ARM',
                        'Debian Trixie(13)', 'Debian Trixie(13) ARM',
                        'Ubuntu Jammy(22.04) tarball', 'Debian Trixie(13) tarball'
                    ]
                    def requestedStages = params.BUILD_STAGES ? params.BUILD_STAGES.split(',').collect { it.trim() } : []
                    if (!requestedStages || requestedStages.any { rpmStages.contains(it) }) {
                        signRPM()
                    }
                    if (!requestedStages || requestedStages.any { debStages.contains(it) }) {
                        signDEB()
                    }
                }
            }
        }
        stage('Push to public repository') {
            steps {
                script {
                    PXC_VERSION_MINOR = sh(returnStdout: true, script: ''' curl -s -O $(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git$||')/${GIT_BRANCH}/MYSQL_VERSION; cat MYSQL_VERSION | grep MYSQL_VERSION_MINOR | awk -F= '{print $2}' ''').trim()
                    if ("${PXC_VERSION_MINOR}" == "0") {
                    // sync packages
                        if (env.FIPSMODE == 'YES') {
                            sync2PrivateProdAutoBuild(params.CLOUD, "pxc-80-pro", COMPONENT)
                        } else {
                            sync2ProdAutoBuild(params.CLOUD, "pxc-80", COMPONENT)
                        }
                    } else {
                        if (env.FIPSMODE == 'YES') {
                            if ("${PXC_VERSION_MINOR}" == "4") {
                                sync2PrivateProdAutoBuild(params.CLOUD, "pxc-84-pro", COMPONENT)
                            } else {
                                sync2PrivateProdAutoBuild(params.CLOUD, "pxc-8x-innovation-pro", COMPONENT)
                            }
                        } else {
                            if ("${PXC_VERSION_MINOR}" == "4") {
                                sync2ProdAutoBuild(params.CLOUD, "pxc-84-lts", COMPONENT)
                            } else {
                                sync2ProdAutoBuild(params.CLOUD, "pxc-8x-innovation", COMPONENT)
                            }
                        }
                    }
                }
            }
        }
        stage('Push Tarballs to TESTING download area') {
            when {
                expression { !params.BUILD_STAGES || params.BUILD_STAGES.split(',').any { it.trim().toLowerCase().contains('tarball') } }
            }
            steps {
                script {
                    try {
                        if (env.FIPSMODE == 'YES') {
                            uploadTarballToDownloadsTesting(params.CLOUD, "pxc-gated", "${GIT_BRANCH}")
                        } else {
                            uploadTarballToDownloadsTesting(params.CLOUD, "pxc", "${GIT_BRANCH}")
                        }
                    }
                    catch (err) {
                        echo "Caught: ${err}"
                        currentBuild.result = 'UNSTABLE'
                    }
                }
            }
        }
        stage('Build docker containers') {
            when {
                expression { env.FIPSMODE == 'NO' }
            }
            agent {
                label 'launcher-x64'
            }
            steps {
                script {
                    build job: 'hetzner-pxc8.0-docker-build',
                          parameters: [
                              string(name: 'CLOUD', value: 'Hetzner'),
                              string(name: 'ORGANIZATION', value: 'perconalab'),
                              string(name: 'GIT_BRANCH', value: "${GIT_BRANCH}"),
                              string(name: 'RPM_RELEASE', value: '1'),
                              string(name: 'DEB_RELEASE', value: '1'),
                              string(name: 'FIPSMODE', value: 'NO'),
                              booleanParam(name: 'RUN_FAST', value: true)
                          ],
                          wait: false
                }
            }
        }
    }
    post {
        success {
            script {
                if (env.FIPSMODE == 'YES') {
                    slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: PRO build has been finished successfully for ${GIT_BRANCH} - [${BUILD_URL}]")
                } else {
                    slackNotify("${SLACKNOTIFY}", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${GIT_BRANCH} - [${BUILD_URL}]")
                }
            }
            deleteDir()
        }
        failure {
            script {
                if (env.FIPSMODE == 'YES') {
                    slackNotify("${SLACKNOTIFY}", "#FF0000", "[${JOB_NAME}]: PRO build failed for ${GIT_BRANCH} - [${BUILD_URL}]")
                } else {
                    slackNotify("${SLACKNOTIFY}", "#FF0000", "[${JOB_NAME}]: build failed for ${GIT_BRANCH} - [${BUILD_URL}]")
                }
            }
            deleteDir()
        }
        always {
            sh '''
                sudo rm -rf ./*
            '''
            script {
                if (env.FIPSMODE == 'YES') {
                    currentBuild.description = "PRO -> Built on ${GIT_BRANCH} - packages [${COMPONENT}/${AWS_STASH_PATH}]"
                } else {
                    currentBuild.description = "Built on ${GIT_BRANCH} - packages [${COMPONENT}/${AWS_STASH_PATH}]"
                }
            }
            deleteDir()
        }
    }
}

library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    withCredentials([
                    string(credentialsId: 'SNYK_TOKEN', variable: 'SNYK_TOKEN'),
                    string(credentialsId: 'SNYK_ORG_TOKEN', variable: 'SNYK_ORG_TOKEN')
                ]){
      sh """
        echo "Starting Build Stage"
        set -o xtrace
        wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${GIT_BRANCH}/packaging/pcsm_sbom/pcsm_generate_sbom.sh -O pcsm_generate_sbom.sh
        pwd -P
        ls -laR
        export build_dir=\$(pwd -P)
        docker run -u root -v \${build_dir}:\${build_dir} -e SNYK_TOKEN=${SNYK_TOKEN} -e SNYK_ORG_TOKEN=${SNYK_ORG_TOKEN} ${DOCKER_OS} sh -c "
            set -o xtrace
            cd \${build_dir}
            ls -laR
            uname -a
            bash -x ./pcsm_generate_sbom.sh --builddir=\${build_dir} --pcsm_version=${PCSM_VERSION} --repo_type=${REPO_TYPE} --git_repo=${GIT_REPO} --git_branch=${GIT_BRANCH} ${STAGE_PARAM}"
            curl -fsSL https://raw.githubusercontent.com/EvgeniyPatlan/sbom_verifier/main/install_sbom_verifier.sh | bash
            bash sbom_verifier.sh pcsm_sbom/*.json
    """
    }
}

void uploadPCSMSBOMToTestingDownloadServer(String productName, String packageVersion, String SBOMType) {

    script {
        try {
            uploadSBOMToDownloadsTesting(params.CLOUD, productName, packageVersion, SBOMType)
        } catch (err) {
            echo "Caught: ${err}"
            currentBuild.result = 'UNSTABLE'
        }
    }
}

void cleanUpWS() {
    sh """
        sudo rm -rf ./*
    """
}

def TIMESTAMP
def AWS_STASH_PATH

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
    }

    parameters {
        choice(
             choices: [ 'Hetzner','AWS' ],
             description: 'Cloud infra for build',
             name: 'CLOUD' )
        string(
            defaultValue: 'https://github.com/percona/percona-clustersync-mongodb.git',
            description: 'URL for pcsm_sbom repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '0.5.0',
            description: 'Version of Percona ClusterSync for MongoDB',
            name: 'PCSM_VERSION')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona-clustersync-mongodb repository',
            name: 'GIT_BRANCH')
	choice(
            choices: 'laboratory\ntesting\nexperimental\nrelease',
            description: 'Packaging repository type',
            name: 'REPO_TYPE')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
    }
    stages {

	stage('Create timestamp') {
            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
            }
            steps {
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting build for ${GIT_BRANCH} - [${BUILD_URL}]")
                cleanUpWS()
		script {
			TIMESTAMP = sh(script: 'date +%Y%m%d%H%M%S', returnStdout: true).trim()
                	sh """
                   		echo ${TIMESTAMP} > timestamp
                   		cat timestamp
                	"""
                    	TIMESTAMP = sh(returnStdout: true, script: "cat timestamp").trim()
                }
                stash includes: 'timestamp', name: 'timestamp'
            }
        }

        stage('Generate SBOM') {
            parallel {
                stage('Generate PCSM SBOM OL/8 AMD') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PCSM_SBOM/${PCSM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PCSM_VERSION}
                                        cat uploadPath-${PCSM_VERSION}
                                """
                                stash includes: "uploadPath-${PCSM_VERSION}", name: "uploadPath-${PCSM_VERSION}"
                                echo "Ran stash includes"
                                buildStage("oraclelinux:8", "")
                                pushArtifactFolder(params.CLOUD, "pcsm_sbom/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Generate PCSM SBOM OL/8 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PCSM_SBOM/${PCSM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PCSM_VERSION}
                                        cat uploadPath-${PCSM_VERSION}
                                """
                                stash includes: "uploadPath-${PCSM_VERSION}", name: "uploadPath-${PCSM_VERSION}"
                                buildStage("oraclelinux:8", "")
                                pushArtifactFolder(params.CLOUD, "pcsm_sbom/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Generate PCSM SBOM OL/9 AMD') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PCSM_SBOM/${PCSM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PCSM_VERSION}
                                        cat uploadPath-${PCSM_VERSION}
                                """
                                stash includes: "uploadPath-${PCSM_VERSION}", name: "uploadPath-${PCSM_VERSION}"
                                buildStage("oraclelinux:9", "")
                                pushArtifactFolder(params.CLOUD, "pcsm_sbom/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Generate PCSM SBOM OL/9 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PCSM_SBOM/${PCSM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PCSM_VERSION}
                                        cat uploadPath-${PCSM_VERSION}
                                """
                                stash includes: "uploadPath-${PCSM_VERSION}", name: "uploadPath-${PCSM_VERSION}"
                                buildStage("oraclelinux:9", "")
                                pushArtifactFolder(params.CLOUD, "pcsm_sbom/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Generate PCSM SBOM AL 2023 AMD') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PCSM_SBOM/${PCSM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PCSM_VERSION}
                                        cat uploadPath-${PCSM_VERSION}
                                """
                                stash includes: "uploadPath-${PCSM_VERSION}", name: "uploadPath-${PCSM_VERSION}"
                                buildStage("amazonlinux:2023", "")
                                pushArtifactFolder(params.CLOUD, "pcsm_sbom/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Generate PCSM SBOM AL 2023 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PCSM_SBOM/${PCSM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PCSM_VERSION}
                                        cat uploadPath-${PCSM_VERSION}
                                """
                                stash includes: "uploadPath-${PCSM_VERSION}", name: "uploadPath-${PCSM_VERSION}"
                                buildStage("amazonlinux:2023", "")
                                pushArtifactFolder(params.CLOUD, "pcsm_sbom/", AWS_STASH_PATH)
                        }
                    }
                }
/*                stage('Generate PCSM SBOM OL/10 AMD') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PCSM_SBOM/${PCSM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PCSM_VERSION}
                                        cat uploadPath-${PCSM_VERSION}
                                """
                                stash includes: "uploadPath-${PCSM_VERSION}", name: "uploadPath-${PCSM_VERSION}"
                                buildStage("oraclelinux:10", "")
                                pushArtifactFolder(params.CLOUD, "pcsm_sbom/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Generate PCSM SBOM OL/10 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PCSM_SBOM/${PCSM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PCSM_VERSION}
                                        cat uploadPath-${PCSM_VERSION}
                                """
                                stash includes: "uploadPath-${PCSM_VERSION}", name: "uploadPath-${PCSM_VERSION}"
                                buildStage("oraclelinux:10", "")
                                pushArtifactFolder(params.CLOUD, "pcsm_sbom/", AWS_STASH_PATH)
                        }
                    }
                } */
                stage('Generate PCSM SBOM Jammy AMD') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PCSM_SBOM/${PCSM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PCSM_VERSION}
                                        cat uploadPath-${PCSM_VERSION}
                                """
                                stash includes: "uploadPath-${PCSM_VERSION}", name: "uploadPath-${PCSM_VERSION}"
                                buildStage("ubuntu:jammy", "")
                                pushArtifactFolder(params.CLOUD, "pcsm_sbom/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Generate PCSM SBOM Jammy ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PCSM_SBOM/${PCSM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PCSM_VERSION}
                                        cat uploadPath-${PCSM_VERSION}
                                """
                                stash includes: "uploadPath-${PCSM_VERSION}", name: "uploadPath-${PCSM_VERSION}"
                                buildStage("ubuntu:jammy", "")
                                pushArtifactFolder(params.CLOUD, "pcsm_sbom/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Generate PCSM SBOM Noble AMD') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PCSM_SBOM/${PCSM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PCSM_VERSION}
                                        cat uploadPath-${PCSM_VERSION}
                                """
                                stash includes: "uploadPath-${PCSM_VERSION}", name: "uploadPath-${PCSM_VERSION}"
                                buildStage("ubuntu:noble", "")
                                pushArtifactFolder(params.CLOUD, "pcsm_sbom/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Generate PCSM SBOM Noble ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PCSM_SBOM/${PCSM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PCSM_VERSION}
                                        cat uploadPath-${PCSM_VERSION}
                                """
                                stash includes: "uploadPath-${PCSM_VERSION}", name: "uploadPath-${PCSM_VERSION}"
                                buildStage("ubuntu:noble", "")
                                pushArtifactFolder(params.CLOUD, "pcsm_sbom/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Generate PCSM SBOM Bullseye AMD') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PCSM_SBOM/${PCSM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PCSM_VERSION}
                                        cat uploadPath-${PCSM_VERSION}
                                """
                                stash includes: "uploadPath-${PCSM_VERSION}", name: "uploadPath-${PCSM_VERSION}"
                                buildStage("debian:bullseye", "")
                                pushArtifactFolder(params.CLOUD, "pcsm_sbom/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Generate PCSM SBOM Bullseye ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PCSM_SBOM/${PCSM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PCSM_VERSION}
                                        cat uploadPath-${PCSM_VERSION}
                                """
                                stash includes: "uploadPath-${PCSM_VERSION}", name: "uploadPath-${PCSM_VERSION}"
                                buildStage("debian:bullseye", "")
                                pushArtifactFolder(params.CLOUD, "pcsm_sbom/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Generate PCSM SBOM Bookworm AMD') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PCSM_SBOM/${PCSM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PCSM_VERSION}
                                        cat uploadPath-${PCSM_VERSION}
                                """
                                stash includes: "uploadPath-${PCSM_VERSION}", name: "uploadPath-${PCSM_VERSION}"
                                buildStage("debian:bookworm", "")
                                pushArtifactFolder(params.CLOUD, "pcsm_sbom/", AWS_STASH_PATH)
                        }
                    }
                }
                stage('Generate PCSM SBOM Bookworm ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PCSM_SBOM/${PCSM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PCSM_VERSION}
                                        cat uploadPath-${PCSM_VERSION}
                                """
                                stash includes: "uploadPath-${PCSM_VERSION}", name: "uploadPath-${PCSM_VERSION}"
                                buildStage("debian:bookworm", "")
                                pushArtifactFolder(params.CLOUD, "pcsm_sbom/", AWS_STASH_PATH)
                        }
                    }
                }
            }  //parallel
        } // stage
            stage('Upload SBOMS from AWS') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        uploadSBOMfromAWS(params.CLOUD, "pcsm_sbom/", AWS_STASH_PATH, "json", "${PCSM_VERSION}")
                    }
                }
                stage('Push SBOMS to TESTING downloads area') {
                    steps {
                        cleanUpWS()
                        uploadPCSMSBOMToTestingDownloadServer("pcsm_sbom", "${PCSM_VERSION}", "json")
                    }
                }
    }
    post {
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${GIT_BRANCH} - [${BUILD_URL}]")
            script {
                currentBuild.description = "Built on ${GIT_BRANCH}"
            }
            deleteDir()
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: build failed for ${GIT_BRANCH} - [${BUILD_URL}]")
            deleteDir()
        }
        always {
            sh '''
                sudo rm -rf ./*
            '''
            deleteDir()
        }
    }
}

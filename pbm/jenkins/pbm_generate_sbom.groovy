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
        wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${GIT_BRANCH}/packaging/pbm_sbom/pbm_generate_sbom.sh -O pbm_generate_sbom.sh
        pwd -P
        ls -laR
        export build_dir=\$(pwd -P)
        docker run -u root -v \${build_dir}:\${build_dir} -e SNYK_TOKEN=${SNYK_TOKEN} -e SNYK_ORG_TOKEN=${SNYK_ORG_TOKEN} ${DOCKER_OS} sh -c "
            set -o xtrace
            cd \${build_dir}
            ls -laR
            uname -a
            bash -x ./pbm_generate_sbom.sh --builddir=\${build_dir} --pbm_version=${PBM_VERSION} --repo_type=${REPO_TYPE} --git_repo=${GIT_REPO} --git_branch=${GIT_BRANCH} ${STAGE_PARAM}"
            curl -fsSL https://raw.githubusercontent.com/EvgeniyPatlan/sbom_verifier/main/install_sbom_verifier.sh | bash
            bash sbom_verifier.sh pbm_sbom/*.json
    """
    }
}

void uploadPBMSBOMToTestingDownloadServer(String productName, String packageVersion, String SBOMType) {

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
            defaultValue: 'https://github.com/percona/percona-backup-mongodb.git',
            description: 'URL for pbm_sbom repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '2.10.0',
            description: 'Version of Percona Backup MongoDB',
            name: 'PBM_VERSION')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona-backup-mongodb repository',
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
                stage('Generate PBM SBOM OL/8 AMD') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PBM_SBOM/${PBM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PBM_VERSION}
                                        cat uploadPath-${PBM_VERSION}
                                """
                                stash includes: "uploadPath-${PBM_VERSION}", name: "uploadPath-${PBM_VERSION}"
                                echo "Ran stash includes"
                                buildStage("oraclelinux:8", "")
                                pushArtifactFolder(params.CLOUD, "pbm_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pbm_sbom/", AWS_STASH_PATH, "json", "${PBM_VERSION}")
                                uploadPBMSBOMToTestingDownloadServer("pbm_sbom", "${PBM_VERSION}", "json")
                        }
                    }
                }
                stage('Generate PBM SBOM OL/8 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PBM_SBOM/${PBM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PBM_VERSION}
                                        cat uploadPath-${PBM_VERSION}
                                """
                                stash includes: "uploadPath-${PBM_VERSION}", name: "uploadPath-${PBM_VERSION}"
                                buildStage("oraclelinux:8", "")
                                pushArtifactFolder(params.CLOUD, "pbm_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pbm_sbom/", AWS_STASH_PATH, "json", "${PBM_VERSION}")
                                uploadPBMSBOMToTestingDownloadServer("pbm_sbom", "${PBM_VERSION}", "json")
                        }
                    }
                }
                stage('Generate PBM SBOM OL/9 AMD') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PBM_SBOM/${PBM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PBM_VERSION}
                                        cat uploadPath-${PBM_VERSION}
                                """
                                stash includes: "uploadPath-${PBM_VERSION}", name: "uploadPath-${PBM_VERSION}"
                                buildStage("oraclelinux:9", "")
                                pushArtifactFolder(params.CLOUD, "pbm_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pbm_sbom/", AWS_STASH_PATH, "json", "${PBM_VERSION}")
                                uploadPBMSBOMToTestingDownloadServer("pbm_sbom", "${PBM_VERSION}", "json")
                        }
                    }
                }
                stage('Generate PBM SBOM OL/9 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PBM_SBOM/${PBM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PBM_VERSION}
                                        cat uploadPath-${PBM_VERSION}
                                """
                                stash includes: "uploadPath-${PBM_VERSION}", name: "uploadPath-${PBM_VERSION}"
                                buildStage("oraclelinux:9", "")
                                pushArtifactFolder(params.CLOUD, "pbm_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pbm_sbom/", AWS_STASH_PATH, "json", "${PBM_VERSION}")
                                uploadPBMSBOMToTestingDownloadServer("pbm_sbom", "${PBM_VERSION}", "json")
                        }
                    }
                }
                stage('Generate PBM SBOM AL 2023 AMD') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PBM_SBOM/${PBM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PBM_VERSION}
                                        cat uploadPath-${PBM_VERSION}
                                """
                                stash includes: "uploadPath-${PBM_VERSION}", name: "uploadPath-${PBM_VERSION}"
                                buildStage("amazonlinux:2023", "")
                                pushArtifactFolder(params.CLOUD, "pbm_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pbm_sbom/", AWS_STASH_PATH, "json", "${PBM_VERSION}")
                                uploadPBMSBOMToTestingDownloadServer("pbm_sbom", "${PBM_VERSION}", "json")
                        }
                    }
                }
                stage('Generate PBM SBOM AL 2023 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PBM_SBOM/${PBM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PBM_VERSION}
                                        cat uploadPath-${PBM_VERSION}
                                """
                                stash includes: "uploadPath-${PBM_VERSION}", name: "uploadPath-${PBM_VERSION}"
                                buildStage("amazonlinux:2023", "")
                                pushArtifactFolder(params.CLOUD, "pbm_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pbm_sbom/", AWS_STASH_PATH, "json", "${PBM_VERSION}")
                                uploadPBMSBOMToTestingDownloadServer("pbm_sbom", "${PBM_VERSION}", "json")
                        }
                    }
                }
/*                stage('Generate PBM SBOM OL/10 AMD') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PBM_SBOM/${PBM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PBM_VERSION}
                                        cat uploadPath-${PBM_VERSION}
                                """
                                stash includes: "uploadPath-${PBM_VERSION}", name: "uploadPath-${PBM_VERSION}"
                                buildStage("oraclelinux:10", "")
                                pushArtifactFolder(params.CLOUD, "pbm_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pbm_sbom/", AWS_STASH_PATH, "json", "${PBM_VERSION}")
                                uploadPBMSBOMToTestingDownloadServer("pbm_sbom", "${PBM_VERSION}", "json")
                        }
                    }
                }
                stage('Generate PBM SBOM OL/10 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PBM_SBOM/${PBM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PBM_VERSION}
                                        cat uploadPath-${PBM_VERSION}
                                """
                                stash includes: "uploadPath-${PBM_VERSION}", name: "uploadPath-${PBM_VERSION}"
                                buildStage("oraclelinux:10", "")
                                pushArtifactFolder(params.CLOUD, "pbm_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pbm_sbom/", AWS_STASH_PATH, "json", "${PBM_VERSION}")
                                uploadPBMSBOMToTestingDownloadServer("pbm_sbom", "${PBM_VERSION}", "json")
                        }
                    }
                } */
                stage('Generate PBM SBOM Jammy AMD') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PBM_SBOM/${PBM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PBM_VERSION}
                                        cat uploadPath-${PBM_VERSION}
                                """
                                stash includes: "uploadPath-${PBM_VERSION}", name: "uploadPath-${PBM_VERSION}"
                                buildStage("ubuntu:jammy", "")
                                pushArtifactFolder(params.CLOUD, "pbm_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pbm_sbom/", AWS_STASH_PATH, "json", "${PBM_VERSION}")
                                uploadPBMSBOMToTestingDownloadServer("pbm_sbom", "${PBM_VERSION}", "json")
                        }
                    }
                }
                stage('Generate PBM SBOM Jammy ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PBM_SBOM/${PBM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PBM_VERSION}
                                        cat uploadPath-${PBM_VERSION}
                                """
                                stash includes: "uploadPath-${PBM_VERSION}", name: "uploadPath-${PBM_VERSION}"
                                buildStage("ubuntu:jammy", "")
                                pushArtifactFolder(params.CLOUD, "pbm_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pbm_sbom/", AWS_STASH_PATH, "json", "${PBM_VERSION}")
                                uploadPBMSBOMToTestingDownloadServer("pbm_sbom", "${PBM_VERSION}", "json")
                        }
                    }
                }
                stage('Generate PBM SBOM Noble AMD') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PBM_SBOM/${PBM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PBM_VERSION}
                                        cat uploadPath-${PBM_VERSION}
                                """
                                stash includes: "uploadPath-${PBM_VERSION}", name: "uploadPath-${PBM_VERSION}"
                                buildStage("ubuntu:noble", "")
                                pushArtifactFolder(params.CLOUD, "pbm_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pbm_sbom/", AWS_STASH_PATH, "json", "${PBM_VERSION}")
                                uploadPBMSBOMToTestingDownloadServer("pbm_sbom", "${PBM_VERSION}", "json")
                        }
                    }
                }
                stage('Generate PBM SBOM Noble ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PBM_SBOM/${PBM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PBM_VERSION}
                                        cat uploadPath-${PBM_VERSION}
                                """
                                stash includes: "uploadPath-${PBM_VERSION}", name: "uploadPath-${PBM_VERSION}"
                                buildStage("ubuntu:noble", "")
                                pushArtifactFolder(params.CLOUD, "pbm_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pbm_sbom/", AWS_STASH_PATH, "json", "${PBM_VERSION}")
                                uploadPBMSBOMToTestingDownloadServer("pbm_sbom", "${PBM_VERSION}", "json")
                        }
                    }
                }
                stage('Generate PBM SBOM Bullseye AMD') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PBM_SBOM/${PBM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PBM_VERSION}
                                        cat uploadPath-${PBM_VERSION}
                                """
                                stash includes: "uploadPath-${PBM_VERSION}", name: "uploadPath-${PBM_VERSION}"
                                buildStage("debian:bullseye", "")
                                pushArtifactFolder(params.CLOUD, "pbm_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pbm_sbom/", AWS_STASH_PATH, "json", "${PBM_VERSION}")
                                uploadPBMSBOMToTestingDownloadServer("pbm_sbom", "${PBM_VERSION}", "json")
                        }
                    }
                }
                stage('Generate PBM SBOM Bookworm AMD') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PBM_SBOM/${PBM_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PBM_VERSION}
                                        cat uploadPath-${PBM_VERSION}
                                """
                                stash includes: "uploadPath-${PBM_VERSION}", name: "uploadPath-${PBM_VERSION}"
                                buildStage("debian:bookworm", "")
                                pushArtifactFolder(params.CLOUD, "pbm_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pbm_sbom/", AWS_STASH_PATH, "json", "${PBM_VERSION}")
                                uploadPBMSBOMToTestingDownloadServer("pbm_sbom", "${PBM_VERSION}", "json")
                        }
                    }
                }
            }  //parallel
        } // stage

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

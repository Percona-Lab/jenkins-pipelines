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
        set -o xtrace
        wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${GIT_BRANCH}/pg_sbom/pg_generate_sbom.sh -O pg_generate_sbom.sh
        pwd -P
        ls -laR
        export build_dir=\$(pwd -P)
        docker run -u root -v \${build_dir}:\${build_dir} -e SNYK_TOKEN=${SNYK_TOKEN} -e SNYK_ORG_TOKEN=${SNYK_ORG_TOKEN} ${DOCKER_OS} sh -c "
            set -o xtrace
            cd \${build_dir}
            bash -x ./pg_generate_sbom.sh --pg_version=${PG_VERSION} --repo_type=${REPO_TYPE} ${STAGE_PARAM}"
            curl -fsSL https://raw.githubusercontent.com/EvgeniyPatlan/sbom_verifier/main/install_sbom_verifier.sh | bash
            bash sbom_verifier.sh pg_sbom/*.json

    """
    }
}

void uploadPGSBOMToTestingDownloadServer(String productName, String packageVersion, String SBOMType) {

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
        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
    }

    parameters {
        choice(
             choices: [ 'Hetzner','AWS' ],
             description: 'Cloud infra for build',
             name: 'CLOUD' )
        string(
            defaultValue: 'https://github.com/percona/postgres-packaging.git',
            description: 'URL for pg_sbom repository',
            name: 'GIT_REPO')
        string(
            defaultValue: '17.4',
            description: 'Version of PostgreSQL server',
            name: 'PG_VERSION')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for postgres packaging repository',
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
                label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
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
                stage('Generate PG SBOM OL/8 AMD') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PG_SBOM/${PG_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PG_VERSION}
                                        cat uploadPath-${PG_VERSION}
                                """
                                stash includes: "uploadPath-${PG_VERSION}", name: "uploadPath-${PG_VERSION}"
                                buildStage("oraclelinux:8", "")
                                pushArtifactFolder(params.CLOUD, "pg_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pg_sbom/", AWS_STASH_PATH, "json", "${PG_VERSION}")
                                uploadPGSBOMToTestingDownloadServer("pg_sbom", "${PG_VERSION}", "json")
                        }
                    }
                }
                stage('Generate PG SBOM OL/8 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PG_SBOM/${PG_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PG_VERSION}
                                        cat uploadPath-${PG_VERSION}
                                """
                                stash includes: "uploadPath-${PG_VERSION}", name: "uploadPath-${PG_VERSION}"
                                buildStage("oraclelinux:8", "")
                                pushArtifactFolder(params.CLOUD, "pg_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pg_sbom/", AWS_STASH_PATH, "json", "${PG_VERSION}")
                                uploadPGSBOMToTestingDownloadServer("pg_sbom", "${PG_VERSION}", "json")
                        }
                    }
                }
                stage('Generate PG SBOM OL/9 AMD') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PG_SBOM/${PG_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PG_VERSION}
                                        cat uploadPath-${PG_VERSION}
                                """
                                stash includes: "uploadPath-${PG_VERSION}", name: "uploadPath-${PG_VERSION}"
                                buildStage("oraclelinux:9", "")
                                pushArtifactFolder(params.CLOUD, "pg_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pg_sbom/", AWS_STASH_PATH, "json", "${PG_VERSION}")
                                uploadPGSBOMToTestingDownloadServer("pg_sbom", "${PG_VERSION}", "json")
                        }
                    }
                }
                stage('Generate PG SBOM OL/9 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PG_SBOM/${PG_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PG_VERSION}
                                        cat uploadPath-${PG_VERSION}
                                """
                                stash includes: "uploadPath-${PG_VERSION}", name: "uploadPath-${PG_VERSION}"
                                buildStage("oraclelinux:9", "")
                                pushArtifactFolder(params.CLOUD, "pg_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pg_sbom/", AWS_STASH_PATH, "json", "${PG_VERSION}")
                                uploadPGSBOMToTestingDownloadServer("pg_sbom", "${PG_VERSION}", "json")
                        }
                    }
                }
                stage('Generate PG SBOM OL/10 AMD') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PG_SBOM/${PG_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PG_VERSION}
                                        cat uploadPath-${PG_VERSION}
                                """
                                stash includes: "uploadPath-${PG_VERSION}", name: "uploadPath-${PG_VERSION}"
                                buildStage("oraclelinux:10", "")
                                pushArtifactFolder(params.CLOUD, "pg_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pg_sbom/", AWS_STASH_PATH, "json", "${PG_VERSION}")
                                uploadPGSBOMToTestingDownloadServer("pg_sbom", "${PG_VERSION}", "json")
                        }
                    }
                }
                stage('Generate PG SBOM OL/10 ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PG_SBOM/${PG_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PG_VERSION}
                                        cat uploadPath-${PG_VERSION}
                                """
                                stash includes: "uploadPath-${PG_VERSION}", name: "uploadPath-${PG_VERSION}"
                                buildStage("oraclelinux:10", "")
                                pushArtifactFolder(params.CLOUD, "pg_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pg_sbom/", AWS_STASH_PATH, "json", "${PG_VERSION}")
                                uploadPGSBOMToTestingDownloadServer("pg_sbom", "${PG_VERSION}", "json")
                        }
                    }
                }
                stage('Generate PG SBOM Jammy AMD') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PG_SBOM/${PG_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PG_VERSION}
                                        cat uploadPath-${PG_VERSION}
                                """
                                stash includes: "uploadPath-${PG_VERSION}", name: "uploadPath-${PG_VERSION}"
                                buildStage("ubuntu:jammy", "")
                                pushArtifactFolder(params.CLOUD, "pg_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pg_sbom/", AWS_STASH_PATH, "json", "${PG_VERSION}")
                                uploadPGSBOMToTestingDownloadServer("pg_sbom", "${PG_VERSION}", "json")
                        }
                    }
                }
                stage('Generate PG SBOM Jammy ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PG_SBOM/${PG_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PG_VERSION}
                                        cat uploadPath-${PG_VERSION}
                                """
                                stash includes: "uploadPath-${PG_VERSION}", name: "uploadPath-${PG_VERSION}"
                                buildStage("ubuntu:jammy", "")
                                pushArtifactFolder(params.CLOUD, "pg_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pg_sbom/", AWS_STASH_PATH, "json", "${PG_VERSION}")
                                uploadPGSBOMToTestingDownloadServer("pg_sbom", "${PG_VERSION}", "json")
                        }
                    }
                }
                stage('Generate PG SBOM Noble AMD') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PG_SBOM/${PG_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PG_VERSION}
                                        cat uploadPath-${PG_VERSION}
                                """
                                stash includes: "uploadPath-${PG_VERSION}", name: "uploadPath-${PG_VERSION}"
                                buildStage("ubuntu:noble", "")
                                pushArtifactFolder(params.CLOUD, "pg_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pg_sbom/", AWS_STASH_PATH, "json", "${PG_VERSION}")
                                uploadPGSBOMToTestingDownloadServer("pg_sbom", "${PG_VERSION}", "json")
                        }
                    }
                }
                stage('Generate PG SBOM Noble ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PG_SBOM/${PG_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PG_VERSION}
                                        cat uploadPath-${PG_VERSION}
                                """
                                stash includes: "uploadPath-${PG_VERSION}", name: "uploadPath-${PG_VERSION}"
                                buildStage("ubuntu:noble", "")
                                pushArtifactFolder(params.CLOUD, "pg_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pg_sbom/", AWS_STASH_PATH, "json", "${PG_VERSION}")
                                uploadPGSBOMToTestingDownloadServer("pg_sbom", "${PG_VERSION}", "json")
                        }
                    }
                }
                stage('Generate PG SBOM Bullseye AMD') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PG_SBOM/${PG_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PG_VERSION}
                                        cat uploadPath-${PG_VERSION}
                                """
                                stash includes: "uploadPath-${PG_VERSION}", name: "uploadPath-${PG_VERSION}"
                                buildStage("debian:bullseye", "")
                                pushArtifactFolder(params.CLOUD, "pg_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pg_sbom/", AWS_STASH_PATH, "json", "${PG_VERSION}")
                                uploadPGSBOMToTestingDownloadServer("pg_sbom", "${PG_VERSION}", "json")
                        }
                    }
                }
                stage('Generate PG SBOM Bullseye ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PG_SBOM/${PG_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PG_VERSION}
                                        cat uploadPath-${PG_VERSION}
                                """
                                stash includes: "uploadPath-${PG_VERSION}", name: "uploadPath-${PG_VERSION}"
                                buildStage("debian:bullseye", "")
                                pushArtifactFolder(params.CLOUD, "pg_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pg_sbom/", AWS_STASH_PATH, "json", "${PG_VERSION}")
                                uploadPGSBOMToTestingDownloadServer("pg_sbom", "${PG_VERSION}", "json")
                        }
                    }
                }
                stage('Generate PG SBOM Bookworm AMD') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PG_SBOM/${PG_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PG_VERSION}
                                        cat uploadPath-${PG_VERSION}
                                """
                                stash includes: "uploadPath-${PG_VERSION}", name: "uploadPath-${PG_VERSION}"
                                buildStage("debian:bookworm", "")
                                pushArtifactFolder(params.CLOUD, "pg_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pg_sbom/", AWS_STASH_PATH, "json", "${PG_VERSION}")
                                uploadPGSBOMToTestingDownloadServer("pg_sbom", "${PG_VERSION}", "json")
                        }
                    }
                }
                stage('Generate PG SBOM Bookworm ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PG_SBOM/${PG_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PG_VERSION}
                                        cat uploadPath-${PG_VERSION}
                                """
                                stash includes: "uploadPath-${PG_VERSION}", name: "uploadPath-${PG_VERSION}"
                                buildStage("debian:bookworm", "")
                                pushArtifactFolder(params.CLOUD, "pg_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pg_sbom/", AWS_STASH_PATH, "json", "${PG_VERSION}")
                                uploadPGSBOMToTestingDownloadServer("pg_sbom", "${PG_VERSION}", "json")
                        }
                    }
                }
				stage('Generate PG SBOM Trixie AMD') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PG_SBOM/${PG_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PG_VERSION}
                                        cat uploadPath-${PG_VERSION}
                                """
                                stash includes: "uploadPath-${PG_VERSION}", name: "uploadPath-${PG_VERSION}"
                                buildStage("debian:trixie", "")
                                pushArtifactFolder(params.CLOUD, "pg_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pg_sbom/", AWS_STASH_PATH, "json", "${PG_VERSION}")
                                uploadPGSBOMToTestingDownloadServer("pg_sbom", "${PG_VERSION}", "json")
                        }
                    }
                }
				stage('Generate PG SBOM Trixie ARM') {
                    agent {
                        label params.CLOUD == 'Hetzner' ? 'docker-aarch64' : 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                unstash 'timestamp'
                                AWS_STASH_PATH="/srv/UPLOAD/${REPO_TYPE}/BUILDS/PG_SBOM/${PG_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH} > uploadPath-${PG_VERSION}
                                        cat uploadPath-${PG_VERSION}
                                """
                                stash includes: "uploadPath-${PG_VERSION}", name: "uploadPath-${PG_VERSION}"
                                buildStage("debian:trixie", "")
                                pushArtifactFolder(params.CLOUD, "pg_sbom/", AWS_STASH_PATH)
                                uploadSBOMfromAWS(params.CLOUD, "pg_sbom/", AWS_STASH_PATH, "json", "${PG_VERSION}")
                                uploadPGSBOMToTestingDownloadServer("pg_sbom", "${PG_VERSION}", "json")
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

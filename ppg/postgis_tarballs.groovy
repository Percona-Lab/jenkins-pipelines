library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void buildStage(String PG_VERION, String DOCKER_OS, String ARCH) {
    withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'TOKEN')]) {
        sh """
           set -o xtrace
           git clone https://${TOKEN}@github.com/percona/postgis-tarballs.git
           cd postgis-tarballs
           pwd -P
           ls -laR
           export build_dir=\$(pwd -P)
           set -o xtrace
           cd \${build_dir}
           if [[ "${DOCKER_OS}" == *el* ]]; then
               bash -x ./postgis_rpms.sh --pg_version=${PG_VERION} --platform=${DOCKER_OS} --architecture=${ARCH}
           else
               bash -x ./postgis_debians.sh --pg_version=${PG_VERION} --platform=${DOCKER_OS} --architecture=${ARCH}
           fi
       """
    }
}

void uploadTarballToTestingDownloadServer(String tarballDirectory, String packageVersion) {

    script {
        try {
            uploadPGTarballToDownloadsTesting(tarballDirectory, packageVersion)
        } catch (err) {
            echo "Caught: ${err}"
            currentBuild.result = 'UNSTABLE'
        }
    }
}

void buildTarball(String platform, String architecture){

     script {
               unstash "uploadPath-${PG_VERSION}"
               buildStage("${PG_VERSION}", platform, architecture)
               pushArtifactFolder("postgis_output/", AWS_STASH_PATH)
               uploadPGTarballfromAWS("postgis_output/", AWS_STASH_PATH, "binary", "${PG_VERSION}")
               uploadTarballToTestingDownloadServer("postgis_tarballs", "${PG_VERSION}")
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
        label 'docker'
    }

    parameters {
        //string(
        //    defaultValue: 'https://github.com/percona/postgis-tarballs.git',
        //    description: 'URL for postgis-tarballs repository',
        //    name: 'GIT_REPO')
        //string(
        //    defaultValue: 'main',
        //    description: 'Tag/Branch for postgis-tarballs repository',
        //    name: 'GIT_BRANCH')
        string(
            defaultValue: '17.2',
            description: 'Version of PostgreSQL server',
            name: 'PG_VERSION')
	choice(
            choices: 'laboratory\ntesting\nexperimental',
            description: 'Repo destination to push packages to',
            name: 'DESTINATION')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
    }
    stages {

	stage('Create timestamp') {
            agent {
                label 'docker'
            }
            steps {
                slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: starting build for ${PG_VERSION} - [${BUILD_URL}]")
                cleanUpWS()
		script {
			TIMESTAMP = sh(script: 'date +%Y%m%d%H%M%S', returnStdout: true).trim()
                	sh """
                   		echo ${TIMESTAMP} > timestamp
                   		cat timestamp
                	"""
                    	TIMESTAMP = sh(returnStdout: true, script: "cat timestamp").trim()
                        def PRODUCT="PostGIS-${PG_VERSION}-Tarballs"
                        AWS_STASH_PATH="/srv/UPLOAD/${DESTINATION}/BUILDS/${PRODUCT}/${TIMESTAMP}"
                        sh """
                                echo ${AWS_STASH_PATH} > uploadPath-${PG_VERSION}
                                cat uploadPath-${PG_VERSION}
                        """
                }
                stash includes: 'timestamp', name: 'timestamp'
                stash includes: "uploadPath-${PG_VERSION}", name: "uploadPath-${PG_VERSION}"
            }
        }

        stage('Build pg_tarballs') {
            parallel {
                stage('Build postgis-tarball for OL8 amd64') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                buildTarball("el8", "amd64")
                        }
                    }
                }
                stage('Build postgis-tarball for OL8 arm64') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                buildTarball("el8", "arm64")
                        }
                    }
                }
                stage('Build postgis-tarball for OL9 amd64') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                buildTarball("el9", "amd64")
                        }
                    }
                }
                stage('Build postgis-tarball for OL9 arm64') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                buildTarball("el9", "arm64")
                        }
                    }
                }
                stage('Build postgis-tarball for Ubuntu focal amd64') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                buildTarball("focal", "amd64")
                        }
                    }
                }
                stage('Build postgis-tarball for Ubuntu focal arm64') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                buildTarball("focal", "arm64")
                        }
                    }
                }
                stage('Build postgis-tarball for Ubuntu jammy amd64') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                buildTarball("jammy", "amd64")
                        }
                    }
                }
                stage('Build postgis-tarball for Ubuntu jammy arm64') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                buildTarball("jammy", "arm64")
                        }
                    }
                }
                stage('Build postgis-tarball for Ubuntu noble amd64') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                buildTarball("noble", "amd64")
                        }
                    }
                }
                stage('Build postgis-tarball for Ubuntu noble arm64') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                buildTarball("noble", "arm64")
                        }
                    }
                }
                stage('Build postgis-tarball for Debian bullseye amd64') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                buildTarball("bullseye", "amd64")
                        }
                    }
                }
                stage('Build postgis-tarball for Debian bullseye arm64') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                buildTarball("bullseye", "arm64")
                        }
                    }
                }
                stage('Build postgis-tarball for Debian bookworm amd64') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                buildTarball("bookworm", "amd64")
                        }
                    }
                }
                stage('Build postgis-tarball for Debian bookworm arm64') {
                    agent {
                        label 'docker-32gb-aarch64'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                buildTarball("bookworm", "arm64")
                        }
                    }
                }
            }  //parallel
        } // stage

    }
    post {
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: build has been finished successfully for ${PG_VERSION} - [${BUILD_URL}]")
            script {
                currentBuild.description = "Built for ${PG_VERSION}"
            }
            deleteDir()
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: build failed for ${PG_VERSION} - [${BUILD_URL}]")
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

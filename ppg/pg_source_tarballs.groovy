library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void buildStage(String DOCKER_OS, String STAGE_PARAM) {
    sh """
        set -o xtrace
        wget \$(echo ${GIT_REPO} | sed -re 's|github.com|raw.githubusercontent.com|; s|\\.git\$||')/${GIT_BRANCH}/pg_tarballs/pg_source_tarballs.sh -O builder.sh
        pwd -P
        ls -laR
        export build_dir=\$(pwd -P)
        docker run -u root -v \${build_dir}:\${build_dir} ${DOCKER_OS} sh -c "
            set -o xtrace
            cd \${build_dir}
            bash -x ./builder.sh ${STAGE_PARAM}"
    """
}

String getPostgreSQLVersion(String BRANCH_NAME, String configureFileName) {
    def packageVersion = sh(script: """
        # Download the configure file
        if [[ "${BRANCH_NAME}" == *TDE* ]]; then
            wget https://raw.githubusercontent.com/Percona-Lab/postgres/${BRANCH_NAME}/configure -O ${configureFileName}
        else
            wget https://raw.githubusercontent.com/postgres/postgres/${BRANCH_NAME}/configure -O ${configureFileName}
        fi
        # Read the PACKAGE_VERSION value from the configure file
        packageVersion=\$(grep -r 'PACKAGE_VERSION=' ${configureFileName} | tr -dc '[. [:digit:]]')

        # Delete configure file
        rm -f ${configureFileName}

        echo "\$packageVersion"
    """, returnStdout: true).trim()

    return packageVersion
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
        string(
            defaultValue: 'https://github.com/percona/postgres-packaging.git',
            description: 'URL for pg_tarballs repository',
            name: 'GIT_REPO')
/*        string(
            defaultValue: '16.1',
            description: 'Version of PostgreSQL server',
            name: 'PG_VERSION')*/
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for pg_tarballs packaging repository',
            name: 'GIT_BRANCH')
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

        stage('Build pg_source_tarballs') {
            parallel {
                stage('Build source tarball for PG 17') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                def PG_VERSION=17
                                def BRANCH_NAME = "TDE_REL_17_STABLE"
                                def PACKAGE_VERSION = getPostgreSQLVersion(BRANCH_NAME, "configure.${PG_VERSION}.ssl3")
                                println "Returned PACKAGE_VERSION: ${PACKAGE_VERSION}"
                                def PRODUCT="Percona-PostgreSQL-Source-Tarballs"
                                unstash 'timestamp'
                                AWS_STASH_PATH_17="/srv/UPLOAD/${DESTINATION}/BUILDS/${PRODUCT}/${PRODUCT}-${PACKAGE_VERSION}/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH_17} > uploadPath-${PACKAGE_VERSION}
                                        cat uploadPath-${PACKAGE_VERSION}
                                """
                                stash includes: "uploadPath-${PACKAGE_VERSION}", name: "uploadPath-${PACKAGE_VERSION}"
                                buildStage("oraclelinux:8", "--version=${PACKAGE_VERSION}")
                                pushArtifactFolder("tarballs-${PACKAGE_VERSION}/", AWS_STASH_PATH_17)
                                uploadPGTarballfromAWS("tarballs-${PACKAGE_VERSION}/", AWS_STASH_PATH_17, "binary", "${PACKAGE_VERSION}")
                                uploadTarballToTestingDownloadServer("pg_tarballs", "${PACKAGE_VERSION}")
                        }
                    }
                }
                stage('Build source tarball for PG 16') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
			script {
				def PG_VERSION=16
				def BRANCH_NAME = "REL_16_STABLE"
				def PACKAGE_VERSION = getPostgreSQLVersion(BRANCH_NAME, "configure.${PG_VERSION}.ssl3")
				println "Returned PACKAGE_VERSION: ${PACKAGE_VERSION}"
				def PRODUCT="Percona-PostgreSQL-Source-Tarballs"
				unstash 'timestamp'
				AWS_STASH_PATH_16="/srv/UPLOAD/${DESTINATION}/BUILDS/${PRODUCT}/${PRODUCT}-${PACKAGE_VERSION}/${TIMESTAMP}"
				sh """
					echo ${AWS_STASH_PATH_16} > uploadPath-${PACKAGE_VERSION}
					cat uploadPath-${PACKAGE_VERSION}
				"""
				stash includes: "uploadPath-${PACKAGE_VERSION}", name: "uploadPath-${PACKAGE_VERSION}"
                        	buildStage("oraclelinux:8", "--version=${PACKAGE_VERSION}")
				pushArtifactFolder("tarballs-${PACKAGE_VERSION}/", AWS_STASH_PATH_16)
				uploadPGTarballfromAWS("tarballs-${PACKAGE_VERSION}/", AWS_STASH_PATH_16, "binary", "${PACKAGE_VERSION}")
				uploadTarballToTestingDownloadServer("pg_tarballs", "${PACKAGE_VERSION}")
			}
                    }
                }
		stage('Build source tarball for PG 15') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
			script {
				def PG_VERSION=15
                        	def BRANCH_NAME = 'REL_15_STABLE'
				def PACKAGE_VERSION = getPostgreSQLVersion(BRANCH_NAME, "configure.${PG_VERSION}.ssl3")

				def PRODUCT="Percona-PostgreSQL-Source-Tarballs"
                        	unstash 'timestamp'
				AWS_STASH_PATH_15="/srv/UPLOAD/${DESTINATION}/BUILDS/${PRODUCT}/${PRODUCT}-${PACKAGE_VERSION}/${TIMESTAMP}"
				sh """
                                        echo ${AWS_STASH_PATH_15} > uploadPath-${PACKAGE_VERSION}
                                        cat uploadPath-${PACKAGE_VERSION}
                                """
				stash includes: "uploadPath-${PACKAGE_VERSION}", name: "uploadPath-${PACKAGE_VERSION}"
                        	buildStage("oraclelinux:8", "--version=${PACKAGE_VERSION}")
				pushArtifactFolder("tarballs-${PACKAGE_VERSION}/", AWS_STASH_PATH_15)
				uploadPGTarballfromAWS("tarballs-${PACKAGE_VERSION}/", AWS_STASH_PATH_15, "binary", "${PACKAGE_VERSION}")
				uploadTarballToTestingDownloadServer("pg_tarballs", "${PACKAGE_VERSION}")
			}
                    }
                }
		stage('Build source tarball for PG 14') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
			script {
                        	def PG_VERSION=14
                        	def BRANCH_NAME = 'REL_14_STABLE'
				def PACKAGE_VERSION = getPostgreSQLVersion(BRANCH_NAME, "configure.${PG_VERSION}.ssl3")
				println "Returned PACKAGE_VERSION: ${PACKAGE_VERSION}"
				def PRODUCT="Percona-PostgreSQL-Source-Tarballs"
                        	unstash 'timestamp'
				AWS_STASH_PATH_14="/srv/UPLOAD/${DESTINATION}/BUILDS/${PRODUCT}/${PRODUCT}-${PACKAGE_VERSION}/${TIMESTAMP}"
				sh """
                                        echo ${AWS_STASH_PATH_14} > uploadPath-${PACKAGE_VERSION}
                                        cat uploadPath-${PACKAGE_VERSION}
                                """
				stash includes: "uploadPath-${PACKAGE_VERSION}", name: "uploadPath-${PACKAGE_VERSION}"
                        	buildStage("oraclelinux:8", "--version=${PACKAGE_VERSION}")
				pushArtifactFolder("tarballs-${PACKAGE_VERSION}/", AWS_STASH_PATH_14)
				uploadPGTarballfromAWS("tarballs-${PACKAGE_VERSION}/", AWS_STASH_PATH_14, "binary", "${PACKAGE_VERSION}")
				uploadTarballToTestingDownloadServer("pg_tarballs", "${PACKAGE_VERSION}")
			}
                    }
                }
		stage('Build source tarball for PG 13') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
			script {
				def PG_VERSION=13
				def BRANCH_NAME = 'REL_13_STABLE'
				def PACKAGE_VERSION = getPostgreSQLVersion(BRANCH_NAME, "configure.${PG_VERSION}.ssl3")
				println "Returned PACKAGE_VERSION: ${PACKAGE_VERSION}"
				def PRODUCT="Percona-PostgreSQL-Source-Tarballs"
				unstash 'timestamp'
				AWS_STASH_PATH_13="/srv/UPLOAD/${DESTINATION}/BUILDS/${PRODUCT}/${PRODUCT}-${PACKAGE_VERSION}/${TIMESTAMP}"
				sh """
                                        echo ${AWS_STASH_PATH_13} > uploadPath-${PACKAGE_VERSION}
                                        cat uploadPath-${PACKAGE_VERSION}
                                """
				stash includes: "uploadPath-${PACKAGE_VERSION}", name: "uploadPath-${PACKAGE_VERSION}"
				buildStage("oraclelinux:8", "--version=${PACKAGE_VERSION}")
				pushArtifactFolder("tarballs-${PACKAGE_VERSION}/", AWS_STASH_PATH_13)
				uploadPGTarballfromAWS("tarballs-${PACKAGE_VERSION}/", AWS_STASH_PATH_13, "binary", "${PACKAGE_VERSION}")
				uploadTarballToTestingDownloadServer("pg_tarballs", "${PACKAGE_VERSION}")
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

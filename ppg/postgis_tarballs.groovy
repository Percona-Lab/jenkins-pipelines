library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

void buildStage(String PG_VERION, String DOCKER_OS, String ARCH) {
    sh """
        set -o xtrace
        git clone ${GIT_REPO}
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
            defaultValue: 'https://github.com/percona/postgis-tarballs.git',
            description: 'URL for postgis-tarballs repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for postgis-tarballs repository',
            name: 'GIT_BRANCH')
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

        stage('Build pg_tarballs') {
            parallel {
                stage('Build postgis-tarball for OL8 amd64') {
                    agent {
                        label 'docker'
                    }
                    steps {
                        cleanUpWS()
                        script {
                                def PRODUCT="PostGIS-${PG_VERSION}-Tarballs"
                                unstash 'timestamp'
                                AWS_STASH_PATH_EL8_AMD64="/srv/UPLOAD/${DESTINATION}/BUILDS/${PRODUCT}/el8/amd64/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH_EL8_AMD64} > uploadPath-el8-amd64
                                        cat uploadPath-el8-amd64
                                """
                                stash includes: "uploadPath-el8-amd64", name: "uploadPath-el8-amd64"
                                buildStage("${PG_VERSION}", "el8", "amd64")
                                pushArtifactFolder("postgis_output/", AWS_STASH_PATH_EL8_AMD64)
                                uploadPGTarballfromAWS("postgis_output/", AWS_STASH_PATH_EL8_AMD64, "binary", "${PG_VERSION}")
                                uploadTarballToTestingDownloadServer("postgis_tarballs", "${PG_VERSION}")
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
                                def PRODUCT="PostGIS-${PG_VERSION}-Tarballs"
                                unstash 'timestamp'
                                AWS_STASH_PATH_EL8_ARM64="/srv/UPLOAD/${DESTINATION}/BUILDS/${PRODUCT}/el8/arm64/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH_EL8_ARM64} > uploadPath-el8-arm64
                                        cat uploadPath-el8-arm64
                                """
                                stash includes: "uploadPath-el8-arm64", name: "uploadPath-el8-arm64"
                                buildStage("${PG_VERSION}", "el8", "arm64")
                                pushArtifactFolder("postgis_output/", AWS_STASH_PATH_EL8_ARM64)
                                uploadPGTarballfromAWS("postgis_output/", AWS_STASH_PATH_EL8_ARM64, "binary", "${PG_VERSION}")
                                uploadTarballToTestingDownloadServer("postgis_tarballs", "${PG_VERSION}")
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
                                def PRODUCT="PostGIS-${PG_VERSION}-Tarballs"
                                unstash 'timestamp'
                                AWS_STASH_PATH_EL9_AMD64="/srv/UPLOAD/${DESTINATION}/BUILDS/${PRODUCT}/el9/amd64/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH_EL9_AMD64} > uploadPath-el9-amd64
                                        cat uploadPath-el9-amd64
                                """
                                stash includes: "uploadPath-el9-amd64", name: "uploadPath-el9-amd64"
                                buildStage("${PG_VERSION}", "el9", "amd64")
                                pushArtifactFolder("postgis_output/", AWS_STASH_PATH_EL9_AMD64)
                                uploadPGTarballfromAWS("postgis_output/", AWS_STASH_PATH_EL9_AMD64, "binary", "${PG_VERSION}")
                                uploadTarballToTestingDownloadServer("postgis_tarballs", "${PG_VERSION}")
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
                                def PRODUCT="PostGIS-${PG_VERSION}-Tarballs"
                                unstash 'timestamp'
                                AWS_STASH_PATH_EL9_ARM64="/srv/UPLOAD/${DESTINATION}/BUILDS/${PRODUCT}/el9/arm64/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH_EL9_ARM64} > uploadPath-el9-arm64
                                        cat uploadPath-el9-arm64
                                """
                                stash includes: "uploadPath-el9-arm64", name: "uploadPath-el9-arm64"
                                buildStage("${PG_VERSION}", "el9", "arm64")
                                pushArtifactFolder("postgis_output/", AWS_STASH_PATH_EL9_ARM64)
                                uploadPGTarballfromAWS("postgis_output/", AWS_STASH_PATH_EL9_ARM64, "binary", "${PG_VERSION}")
                                uploadTarballToTestingDownloadServer("postgis_tarballs", "${PG_VERSION}")
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
                                def PRODUCT="PostGIS-${PG_VERSION}-Tarballs"
                                unstash 'timestamp'
                                AWS_STASH_PATH_FOCAL_AMD64="/srv/UPLOAD/${DESTINATION}/BUILDS/${PRODUCT}/focal/amd64/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH_FOCAL_AMD64} > uploadPath-focal-amd64
                                        cat uploadPath-focal-amd64
                                """
                                stash includes: "uploadPath-focal-amd64", name: "uploadPath-focal-amd64"
                                buildStage("${PG_VERSION}", "focal", "amd64")
                                pushArtifactFolder("postgis_output/", AWS_STASH_PATH_FOCAL_AMD64)
                                uploadPGTarballfromAWS("postgis_output/", AWS_STASH_PATH_FOCAL_AMD64, "binary", "${PG_VERSION}")
                                uploadTarballToTestingDownloadServer("postgis_tarballs", "${PG_VERSION}")
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
                                def PRODUCT="PostGIS-${PG_VERSION}-Tarballs"
                                unstash 'timestamp'
                                AWS_STASH_PATH_FOCAL_ARM64="/srv/UPLOAD/${DESTINATION}/BUILDS/${PRODUCT}/focal/arm64/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH_FOCAL_ARM64} > uploadPath-focal-arm64
                                        cat uploadPath-focal-arm64
                                """
                                stash includes: "uploadPath-focal-arm64", name: "uploadPath-focal-arm64"
                                buildStage("${PG_VERSION}", "focal", "arm64")
                                pushArtifactFolder("postgis_output/", AWS_STASH_PATH_FOCAL_ARM64)
                                uploadPGTarballfromAWS("postgis_output/", AWS_STASH_PATH_FOCAL_ARM64, "binary", "${PG_VERSION}")
                                uploadTarballToTestingDownloadServer("postgis_tarballs", "${PG_VERSION}")
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
                                def PRODUCT="PostGIS-${PG_VERSION}-Tarballs"
                                unstash 'timestamp'
                                AWS_STASH_PATH_JAMMY_AMD64="/srv/UPLOAD/${DESTINATION}/BUILDS/${PRODUCT}/jammy/amd64/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH_JAMMY_AMD64} > uploadPath-jammy-amd64
                                        cat uploadPath-jammy-amd64
                                """
                                stash includes: "uploadPath-jammy-amd64", name: "uploadPath-jammy-amd64"
                                buildStage("${PG_VERSION}", "jammy", "amd64")
                                pushArtifactFolder("postgis_output/", AWS_STASH_PATH_JAMMY_AMD64)
                                uploadPGTarballfromAWS("postgis_output/", AWS_STASH_PATH_JAMMY_AMD64, "binary", "${PG_VERSION}")
                                uploadTarballToTestingDownloadServer("postgis_tarballs", "${PG_VERSION}")
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
                                def PRODUCT="PostGIS-${PG_VERSION}-Tarballs"
                                unstash 'timestamp'
                                AWS_STASH_PATH_JAMMY_ARM64="/srv/UPLOAD/${DESTINATION}/BUILDS/${PRODUCT}/jammy/arm64/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH_JAMMY_ARM64} > uploadPath-jammy-arm64
                                        cat uploadPath-jammy-arm64
                                """
                                stash includes: "uploadPath-jammy-arm64", name: "uploadPath-jammy-arm64"
                                buildStage("${PG_VERSION}", "jammy", "arm64")
                                pushArtifactFolder("postgis_output/", AWS_STASH_PATH_JAMMY_ARM64)
                                uploadPGTarballfromAWS("postgis_output/", AWS_STASH_PATH_JAMMY_ARM64, "binary", "${PG_VERSION}")
                                uploadTarballToTestingDownloadServer("postgis_tarballs", "${PG_VERSION}")
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
                                def PRODUCT="PostGIS-${PG_VERSION}-Tarballs"
                                unstash 'timestamp'
                                AWS_STASH_PATH_NOBLE_AMD64="/srv/UPLOAD/${DESTINATION}/BUILDS/${PRODUCT}/noble/amd64/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH_NOBLE_AMD64} > uploadPath-noble-amd64
                                        cat uploadPath-noble-amd64
                                """
                                stash includes: "uploadPath-noble-amd64", name: "uploadPath-noble-amd64"
                                buildStage("${PG_VERSION}", "noble", "amd64")
                                pushArtifactFolder("postgis_output/", AWS_STASH_PATH_NOBLE_AMD64)
                                uploadPGTarballfromAWS("postgis_output/", AWS_STASH_PATH_NOBLE_AMD64, "binary", "${PG_VERSION}")
                                uploadTarballToTestingDownloadServer("postgis_tarballs", "${PG_VERSION}")
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
                                def PRODUCT="PostGIS-${PG_VERSION}-Tarballs"
                                unstash 'timestamp'
                                AWS_STASH_PATH_NOBLE_ARM64="/srv/UPLOAD/${DESTINATION}/BUILDS/${PRODUCT}/noble/arm64/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH_NOBLE_ARM64} > uploadPath-noble-arm64
                                        cat uploadPath-noble-arm64
                                """
                                stash includes: "uploadPath-noble-arm64", name: "uploadPath-noble-arm64"
                                buildStage("${PG_VERSION}", "noble", "arm64")
                                pushArtifactFolder("postgis_output/", AWS_STASH_PATH_NOBLE_ARM64)
                                uploadPGTarballfromAWS("postgis_output/", AWS_STASH_PATH_NOBLE_ARM64, "binary", "${PG_VERSION}")
                                uploadTarballToTestingDownloadServer("postgis_tarballs", "${PG_VERSION}")
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
                                def PRODUCT="PostGIS-${PG_VERSION}-Tarballs"
                                unstash 'timestamp'
                                AWS_STASH_PATH_BULLSEYE_AMD64="/srv/UPLOAD/${DESTINATION}/BUILDS/${PRODUCT}/bullseye/amd64/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH_BULLSEYE_AMD64} > uploadPath-bullseye-amd64
                                        cat uploadPath-bullseye-amd64
                                """
                                stash includes: "uploadPath-bullseye-amd64", name: "uploadPath-bullseye-amd64"
                                buildStage("${PG_VERSION}", "bullseye", "amd64")
                                pushArtifactFolder("postgis_output/", AWS_STASH_PATH_BULLSEYE_AMD64)
                                uploadPGTarballfromAWS("postgis_output/", AWS_STASH_PATH_BULLSEYE_AMD64, "binary", "${PG_VERSION}")
                                uploadTarballToTestingDownloadServer("postgis_tarballs", "${PG_VERSION}")
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
                                def PRODUCT="PostGIS-${PG_VERSION}-Tarballs"
                                unstash 'timestamp'
                                AWS_STASH_PATH_BULLSEYE_ARM64="/srv/UPLOAD/${DESTINATION}/BUILDS/${PRODUCT}/bullseye/arm64/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH_BULLSEYE_ARM64} > uploadPath-bullseye-arm64
                                        cat uploadPath-bullseye-arm64
                                """
                                stash includes: "uploadPath-bullseye-arm64", name: "uploadPath-bullseye-arm64"
                                buildStage("${PG_VERSION}", "bullseye", "arm64")
                                pushArtifactFolder("postgis_output/", AWS_STASH_PATH_BULLSEYE_ARM64)
                                uploadPGTarballfromAWS("postgis_output/", AWS_STASH_PATH_BULLSEYE_ARM64, "binary", "${PG_VERSION}")
                                uploadTarballToTestingDownloadServer("postgis_tarballs", "${PG_VERSION}")
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
                                def PRODUCT="PostGIS-${PG_VERSION}-Tarballs"
                                unstash 'timestamp'
                                AWS_STASH_PATH_BOOKWORM_AMD64="/srv/UPLOAD/${DESTINATION}/BUILDS/${PRODUCT}/bookworm/amd64/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH_BOOKWORM_AMD64} > uploadPath-bookworm-amd64
                                        cat uploadPath-bookworm-amd64
                                """
                                stash includes: "uploadPath-bookworm-amd64", name: "uploadPath-bookworm-amd64"
                                buildStage("${PG_VERSION}", "bookworm", "amd64")
                                pushArtifactFolder("postgis_output/", AWS_STASH_PATH_BOOKWORM_AMD64)
                                uploadPGTarballfromAWS("postgis_output/", AWS_STASH_PATH_BOOKWORM_AMD64, "binary", "${PG_VERSION}")
                                uploadTarballToTestingDownloadServer("postgis_tarballs", "${PG_VERSION}")
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
                                def PRODUCT="PostGIS-${PG_VERSION}-Tarballs"
                                unstash 'timestamp'
                                AWS_STASH_PATH_BOOKWORM_ARM64="/srv/UPLOAD/${DESTINATION}/BUILDS/${PRODUCT}/bookworm/arm64/${TIMESTAMP}"
                                sh """
                                        echo ${AWS_STASH_PATH_BOOKWORM_ARM64} > uploadPath-bookworm-arm64
                                        cat uploadPath-bookworm-arm64
                                """
                                stash includes: "uploadPath-bookworm-arm64", name: "uploadPath-bookworm-arm64"
                                buildStage("${PG_VERSION}", "bookworm", "arm64")
                                pushArtifactFolder("postgis_output/", AWS_STASH_PATH_BOOKWORM_ARM64)
                                uploadPGTarballfromAWS("postgis_output/", AWS_STASH_PATH_BOOKWORM_ARM64, "binary", "${PG_VERSION}")
                                uploadTarballToTestingDownloadServer("postgis_tarballs", "${PG_VERSION}")
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

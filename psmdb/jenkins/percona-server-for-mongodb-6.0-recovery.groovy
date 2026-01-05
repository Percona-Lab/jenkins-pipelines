library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

/*
 * Recovery Pipeline for PSMDB 6.0
 * 
 * This pipeline handles post-build stages that may have failed in the main pipeline:
 * - Upload packages and tarballs from S3
 * - Sign packages
 * - Push to public repository
 * - Push Tarballs to TESTING download area
 *
 * Use this when the main pipeline failed at any of these stages.
 */

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'launcher-x64' : 'micro-amazon'
    }
    parameters {
        choice(
            choices: ['Hetzner','AWS'],
            description: 'Cloud infra (must match original build)',
            name: 'CLOUD')
        string(
            defaultValue: '',
            description: 'AWS stash path from the failed build (e.g., psmdb-60/psmdb-6.0.20-17/123). Check the original build console output for this value.',
            name: 'AWS_STASH_PATH')
        string(
            defaultValue: '6.0.0',
            description: 'PSMDB version (e.g., 6.0.20)',
            name: 'PSMDB_VERSION')
        string(
            defaultValue: '1',
            description: 'PSMDB release value (e.g., 17)',
            name: 'PSMDB_RELEASE')
        string(
            defaultValue: 'psmdb-60',
            description: 'PSMDB repo name',
            name: 'PSMDB_REPO')
        choice(
            choices: 'laboratory\ntesting\nexperimental',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
        booleanParam(
            defaultValue: true,
            description: 'Run: Upload packages and tarballs from S3',
            name: 'RUN_UPLOAD')
        booleanParam(
            defaultValue: true,
            description: 'Run: Sign packages',
            name: 'RUN_SIGN')
        booleanParam(
            defaultValue: true,
            description: 'Run: Push to public repository',
            name: 'RUN_PUSH_REPO')
        booleanParam(
            defaultValue: true,
            description: 'Run: Push Tarballs to TESTING download area',
            name: 'RUN_PUSH_TARBALLS')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps()
    }
    stages {
        stage('Validate and Prepare') {
            steps {
                script {
                    if (params.AWS_STASH_PATH == '') {
                        error("AWS_STASH_PATH is required. Check the original build console output for this value.")
                    }
                    if (params.PSMDB_VERSION == '' || params.PSMDB_VERSION == '6.0.0') {
                        echo "WARNING: PSMDB_VERSION appears to be default value. Ensure this is correct."
                    }
                    
                    // Construct uploadPath from AWS_STASH_PATH
                    // Original: REPO_UPLOAD_PATH = UPLOAD/experimental/<AWS_STASH_PATH>
                    // AWS_STASH_PATH = REPO_UPLOAD_PATH without "UPLOAD/experimental/"
                    def REPO_UPLOAD_PATH = "UPLOAD/experimental/${params.AWS_STASH_PATH}"
                    
                    echo """
                    ===== Recovery Pipeline Configuration =====
                    CLOUD: ${params.CLOUD}
                    AWS_STASH_PATH: ${params.AWS_STASH_PATH}
                    REPO_UPLOAD_PATH (constructed): ${REPO_UPLOAD_PATH}
                    PSMDB_VERSION: ${params.PSMDB_VERSION}
                    PSMDB_RELEASE: ${params.PSMDB_RELEASE}
                    PSMDB_REPO: ${params.PSMDB_REPO}
                    COMPONENT: ${params.COMPONENT}
                    
                    Stages to run:
                    - Upload packages and tarballs: ${params.RUN_UPLOAD}
                    - Sign packages: ${params.RUN_SIGN}
                    - Push to public repository: ${params.RUN_PUSH_REPO}
                    - Push tarballs to TESTING: ${params.RUN_PUSH_TARBALLS}
                    ============================================
                    """
                    
                    // Create uploadPath file (required by uploadRPMfromAWS, uploadDEBfromAWS, uploadTarballfromAWS)
                    writeFile file: 'uploadPath', text: REPO_UPLOAD_PATH
                    echo "Created uploadPath file with content: ${REPO_UPLOAD_PATH}"
                }
                stash includes: 'uploadPath', name: 'uploadPath'
            }
        }

        stage('Upload packages and tarballs from S3') {
            when {
                expression { return params.RUN_UPLOAD == true }
            }
            agent {
                label params.CLOUD == 'Hetzner' ? 'docker-x64' : 'docker-64gb'
            }
            steps {
                script {
                    sh "sudo rm -rf ./*"
                }
                uploadRPMfromAWS(params.CLOUD, "rpm/", params.AWS_STASH_PATH)
                uploadDEBfromAWS(params.CLOUD, "deb/", params.AWS_STASH_PATH)
                uploadTarballfromAWS(params.CLOUD, "tarball/", params.AWS_STASH_PATH, 'binary')
            }
        }

        stage('Sign packages') {
            when {
                expression { return params.RUN_SIGN == true }
            }
            steps {
                signRPM()
                signDEB()
            }
        }

        stage('Push to public repository') {
            when {
                expression { return params.RUN_PUSH_REPO == true }
            }
            steps {
                script {
                    sync2ProdAutoBuild(params.CLOUD, params.PSMDB_REPO, params.COMPONENT)
                }
            }
        }

        stage('Push Tarballs to TESTING download area') {
            when {
                expression { return params.RUN_PUSH_TARBALLS == true }
            }
            steps {
                script {
                    try {
                        uploadTarballToDownloadsTesting(params.CLOUD, "psmdb", "${params.PSMDB_VERSION}")
                    }
                    catch (err) {
                        echo "Caught: ${err}"
                        currentBuild.result = 'UNSTABLE'
                    }
                }
            }
        }
    }
    post {
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: Recovery completed successfully - [${BUILD_URL}]")
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: Recovery FAILED - [${BUILD_URL}]")
        }
    }
}

library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

/*
 * Sign RPM packages already present on repo.ci.percona.com and push them
 * to a public yum repository.
 *
 * UPLOAD_PATH must point at an existing build tree on the signing server,
 * e.g. UPLOAD/experimental/BUILDS/postgresql/foo/123
 */

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'launcher-x64' : 'micro-amazon'
    }
    parameters {
        choice(
            choices: ['Hetzner', 'AWS'],
            description: 'Cloud infra (select the one used for repo access)',
            name: 'CLOUD')
        string(
            defaultValue: '',
            description: 'Path on repo.ci.percona.com (e.g. UPLOAD/experimental/BUILDS/foo/bar)',
            name: 'UPLOAD_PATH')
        string(
            defaultValue: 'ppg-17.6',
            description: 'Target repository name',
            name: 'PPG_REPO')
        choice(
            choices: 'laboratory\ntesting\nexperimental',
            description: 'Repo component to push packages to',
            name: 'COMPONENT')
        booleanParam(
            defaultValue: true,
            description: 'Run: Sign RPM packages',
            name: 'RUN_SIGN')
        booleanParam(
            defaultValue: true,
            description: 'Run: Push to public repository',
            name: 'RUN_PUSH_REPO')
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
        timestamps()
    }
    stages {
        stage('Prepare') {
            steps {
                script {
                    if (params.UPLOAD_PATH == '') {
                        error('UPLOAD_PATH is required')
                    }

                    def uploadPath = params.UPLOAD_PATH.trim()
                    if (!uploadPath.startsWith('UPLOAD/')) {
                        uploadPath = "UPLOAD/${uploadPath}"
                    }

                    echo """
                    ===== Sign and Upload RPM =====
                    CLOUD       : ${params.CLOUD}
                    UPLOAD_PATH : ${uploadPath}
                    PPG_REPO    : ${params.PPG_REPO}
                    COMPONENT   : ${params.COMPONENT}
                    RUN_SIGN    : ${params.RUN_SIGN}
                    RUN_PUSH    : ${params.RUN_PUSH_REPO}
                    ===============================
                    """

                    writeFile file: 'uploadPath', text: uploadPath
                }
                stash includes: 'uploadPath', name: 'uploadPath'
            }
        }

        stage('Sign packages') {
            when {
                expression { return params.RUN_SIGN == true }
            }
            steps {
                signRPM(params.CLOUD)
            }
        }

        stage('Push to public repository') {
            when {
                expression { return params.RUN_PUSH_REPO == true }
            }
            steps {
                sync2ProdAutoBuildPG(params.CLOUD, params.PPG_REPO, params.COMPONENT)
            }
        }
    }
    post {
        always {
            deleteDir()
        }
    }
}

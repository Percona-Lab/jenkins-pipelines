pipeline {
    parameters {
        choice(
            choices: ['percona-server-mongodb-operator', 'percona-xtradb-cluster-operator', 'percona-postgresql-operator', 'percona-server-mysql-operator'],
            description: 'Which operator to generate the bundle for',
            name: 'OPERATOR')
        choice(
            choices: ['community', 'redhat', 'marketplace', 'all'],
            description: 'Which bundle to generate',
            name: 'BUNDLE_TYPE')
        string(
            defaultValue: '',
            description: 'The version of the bundle to build',
            name: 'VERSION')
        string(
            defaultValue: '',
            description: 'The range of supported OpenShift versions, e.g. "v4.16-v4.19"',
            name: 'OPENSHIFT_VERSIONS')
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for the operator repository',
            name: 'GIT_BRANCH')
    }
    agent {
        label 'docker-x64-min'
    }
    stages {
        stage('Prepare') {
            steps {
                git branch: 'master', url: 'https://github.com/Percona-Lab/jenkins-pipelines'

                sh """
                    rm -rf source
                    git clone -b ${GIT_BRANCH} https://github.com/percona/${params.OPERATOR} source
                    cd source/installers/olm
                    make tools
                """
            }
        }
        stage('Generate bundle') {
            steps {
                script {
                    if (params.BUNDLE_TYPE == 'all') {
                        sh """
                            cd source/installers/olm
                            make bundles \
                                VERSION=${params.VERSION} \
                                OPENSHIFT_VERSIONS=${params.OPENSHIFT_VERSIONS}
                        """
                    } else {
                        sh """
                            cd source/installers/olm
                            make bundles/${params.BUNDLE_TYPE} \
                                VERSION=${params.VERSION} \
                                OPENSHIFT_VERSIONS=${params.OPENSHIFT_VERSIONS}
                        """
                    }
                    archiveArtifacts artifacts: 'source/installers/olm/bundles/**', allowEmptyArchive: true
                }
            }
        }
        stage('Build') {
            when {
                expression { return params.BUNDLE_TYPE == 'community' }
            }
            steps {
                sh """
                    cd source/installers/olm
                    make build \
                        VERSION=${params.VERSION}
                """
            }
        }
        stage('Push') {
            when {
                expression { return params.BUNDLE_TYPE == 'community' }
            }
            steps {
                 sh """
                    cd source/installers/olm
                    make push \
                        VERSION=${params.VERSION}
                """
            }
        }
    }
}

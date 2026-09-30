library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

def defaultComponentRepo = [
    pg_audit          : 'https://github.com/pgaudit/pgaudit.git',
    pg_repack         : 'https://github.com/reorg/pg_repack.git',
    patroni           : 'https://github.com/zalando/patroni.git',
    pgbackrest        : 'https://github.com/pgbackrest/pgbackrest.git',
    pgpool            : 'https://github.com/pgpool/pgpool2.git',
    postgis           : 'https://github.com/postgis/postgis.git',
    pgaudit13_set_user: 'https://github.com/pgaudit/set_user.git',
    pgbadger          : 'https://github.com/darold/pgbadger.git',
    pgbouncer         : 'https://github.com/pgbouncer/pgbouncer.git',
    pgvector          : 'https://github.com/pgvector/pgvector.git',
    wal2json          : 'https://github.com/eulerto/wal2json.git',
]

def sendSlackNotification(componentName, ppgVersion, componentVersion) {
    if (currentBuild.result == "SUCCESS") {
        buildSummary = "Job: ${env.JOB_NAME}\nComponent: ${componentName}\nComponent Version: ${componentVersion}\nPPG Version: ${ppgVersion}\nStatus: *SUCCESS*\nBuild Report: ${env.BUILD_URL}"
        slackSend color: "good", message: "${buildSummary}", channel: '#postgresql-test'
    } else {
        buildSummary = "Job: ${env.JOB_NAME}\nComponent: ${componentName}\nComponent Version: ${componentVersion}\nPPG Version: ${ppgVersion}\nStatus: *FAILURE*\nBuild number: ${env.BUILD_NUMBER}\nBuild Report :${env.BUILD_URL}"
        slackSend color: "danger", message: "${buildSummary}", channel: '#postgresql-test'
    }
}

pipeline {
    agent {
        label 'min-ol-9-x64'
    }

    parameters {
        choice(
            name: 'REPO',
            description: 'Package repo channel, passed to percona-release enable-only &lt;VERSION&gt; &lt;REPO&gt;. With USE_OBS_REPO it selects the OBS channel instead.',
            choices: [
                'testing',
                'experimental',
                'release'
            ]
        )
        booleanParam(
            name: 'USE_OBS_REPO',
            defaultValue: false,
            description: 'Install PPG packages from the OBS repo (isv:percona:ppg:&lt;channel&gt;:&lt;major_version&gt;) instead of repo.percona.com. REPO maps to the OBS channel: testing-&gt;staging, release-&gt;releases, experimental-&gt;devel; the major version is taken from VERSION.'
        )
        string(
            name: 'OBS_HOST',
            defaultValue: '',
            description: 'OBS instance hostname to use when USE_OBS_REPO is enabled. Leave empty for the default public instance (download.opensuse.org).'
        )
        string(
            defaultValue: 'main',
            description: 'Branch of the ppg-testing repo to run the tests from',
            name: 'TEST_BRANCH'
        )
        string(
            defaultValue: '',
            description: 'Git URL of the upstream PRODUCT source whose test suite runs against the installed package. Leave empty to auto-select from PRODUCT.',
            name: 'COMPONENT_REPO'
        )
        string(
            defaultValue: 'master',
            description: 'Tag of COMPONENT_REPO to check out for the test suite; should match the packaged version.',
            name: 'COMPONENT_VERSION'
        )
        string(
            defaultValue: 'ppg-18.3',
            description: 'PPG version to install, as ppg-&lt;major&gt;.&lt;minor&gt; (e.g. ppg-18.3). Selects the percona-release repo and the PG major.',
            name: 'VERSION'
        )
        choice(
            name: 'PRODUCT',
            description: 'Component to test: runs the &lt;PRODUCT&gt;/setup scenario and picks the default COMPONENT_REPO.',
            choices: [
                'pg_audit',
                'pg_repack',
                'patroni',
                'pgbackrest',
                'pgpool',
                'postgis',
                'pgaudit13_set_user',
                'pgbadger',
                'pgbouncer',
                'pgvector',
                'wal2json'
            ]
        )
        booleanParam(
            name: 'DESTROY_ENV',
            defaultValue: true,
            description: 'Destroy VM after tests'
        )
    }
    environment {
        PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin'
        MOLECULE_DIR = "${PRODUCT}/setup"
    }
    options {
        withCredentials(moleculeDistributionJenkinsCreds())
        buildDiscarder(logRotator(
            daysToKeepStr: '30',
            numToKeepStr: '100',
            artifactNumToKeepStr: '10'
        ))
        retry(conditions: [agent()], count: 2)
    }
    stages {
        stage('Set build name') {
            steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${env.VERSION}-${env.PRODUCT}-${env.COMPONENT_VERSION}-parallel"
                    if (!params.COMPONENT_REPO?.trim()) {
                        env.COMPONENT_REPO = defaultComponentRepo[params.PRODUCT]
                    }
                }
            }
        }
        stage('Checkout') {
            steps {
                deleteDir()
                git poll: false, branch: env.TEST_BRANCH, url: 'https://github.com/Percona-QA/ppg-testing.git'
            }
        }
        stage('Prepare') {
            steps {
                script {
                    installMoleculePython39()
                    sh "python3 tools/render.py --group ${PRODUCT}/setup"
                }
            }
        }
        stage('Test') {
            steps {
                script {
                    moleculeParallelTestPPG(ppgArchitectures(), env.MOLECULE_DIR)
                }
            }
        }
    }
    post {
        always {
            script {
                if (params.DESTROY_ENV) {
                    echo "DESTROY_ENV is true. Cleaning up resources..."
                    moleculeParallelPostDestroyPPG(ppgArchitectures(), env.MOLECULE_DIR)
                } else {
                    echo "DESTROY_ENV is false. Leaving VMs active for debugging."
                }
                sendSlackNotification(env.PRODUCT, env.VERSION, env.COMPONENT_VERSION)
            }
            archiveArtifacts(
                artifacts: "${env.MOLECULE_DIR}/artifacts/**/*.tar.gz",
                allowEmptyArchive: true
            )
        }
    }
}

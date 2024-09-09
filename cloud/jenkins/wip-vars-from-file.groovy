
void runTest() {
        sh """
        echo "Running test"
        echo "OPERATOR_IMAGE=$OPERATOR_IMAGE"
        echo "IMAGE_MONGOD=$IMAGE_MONGOD"
        echo "IMAGE_BACKUP=$IMAGE_BACKUP"
        echo "IMAGE_PMM_CLIENT=$IMAGE_PMM_CLIENT"
        echo "IMAGE_PMM_SERVER=$IMAGE_PMM_SERVER"
        """
}

void verifyParams() {
    if ("$RELEASE_RUN" == "YES" && (!"$PILLAR_VERSION" && !"$IMAGE_MONGOD")){
        echo "This is RELEASE_RUN and either PILLAR_VERSION or IMAGE_MONGOD is provided"
    }
}

void getImages() {
    if ("$RELEASE_RUN" == "YES") {
        versions_file="https://raw.githubusercontent.com/percona/percona-server-mongodb-operator/K8SPSMDB-1136_images_file/e2e-tests/release_images"
        if ("$OPERATOR_IMAGE") {
            echo "OPERATOR_IMAGE was provided. Not doing anything"}
        else{
            echo "OPERATOR_IMAGE was NOT provided. Will use file params!"
            OPERATOR_IMAGE="""${sh(
                returnStdout: true,
                script: "curl -s ${versions_file} | egrep \"OPERATOR_IMAGE=\" | cut -d = -f 2 | tr -d \'\"\' "
            )}"""
            echo "OPERATOR_IMAGE is $OPERATOR_IMAGE "
        }
        if ("$IMAGE_MONGOD") {
            echo "IMAGE_MONGOD was provided. Not doing anything"}
        else{
            echo "IMAGE_MONGOD was NOT provided. Will use file params!"
            IMAGE_MONGOD="""${sh(
                returnStdout: true,
                script: "curl -s ${versions_file} | egrep \"IMAGE_MONGOD${PILLAR_VERSION}=\" | cut -d = -f 2 | tr -d \'\"\' "
            )}"""
            echo "IMAGE_MONGOD is $IMAGE_MONGOD "
        }
        if ("$IMAGE_BACKUP") {
            echo "IMAGE_BACKUP was provided. Not doing anything"}
        else{
            echo "IMAGE_BACKUP was NOT provided. Will use file params!"
            IMAGE_BACKUP="""${sh(
                returnStdout: true,
                script: "curl -s ${versions_file} | egrep \"IMAGE_BACKUP=\" | cut -d = -f 2 | tr -d \'\"\' "
            )}"""
            echo "IMAGE_BACKUP is $IMAGE_BACKUP "
        }
        if ("$IMAGE_PMM_CLIENT") {
            echo "IMAGE_PMM_CLIENT was provided. Not doing anything"}
        else{
            echo "IMAGE_PMM_CLIENT was NOT provided. Will use file params!"
            IMAGE_PMM_CLIENT="""${sh(
                returnStdout: true,
                script: "curl -s ${versions_file} | egrep \"IMAGE_PMM_CLIENT=\" | cut -d = -f 2 | tr -d \'\"\' "
            )}"""
            echo "IMAGE_PMM_CLIENT is $IMAGE_PMM_CLIENT "
        }
        if ("$IMAGE_PMM_SERVER") {
            echo "IMAGE_PMM_SERVER was provided. Not doing anything"}
        else{
            echo "IMAGE_PMM_SERVER was NOT provided. Will use file params!"
            IMAGE_PMM_SERVER="""${sh(
                returnStdout: true,
                script: "curl -s ${versions_file} | egrep \"IMAGE_PMM_SERVER=\" | cut -d = -f 2 | tr -d \'\"\' "
            )}"""
            echo "IMAGE_PMM_SERVER is $IMAGE_PMM_SERVER "
    //echo '{"foo": 0}' | jq .foo
        }
    } else {
        echo "This is not release run. Using params only!"
    }
    runTest()
}

pipeline {
    environment {
        CLEAN_NAMESPACE = 1
    }
    agent any
    parameters {
        choice(
            choices: ['run-release.csv', 'run-distro.csv'],
            description: 'Choose test suite from file (e2e-tests/run-*), used only if TEST_LIST not specified.',
            name: 'TEST_SUITE')
        text(
            defaultValue: '',
            description: 'List of tests to run separated by new line',
            name: 'TEST_LIST')
        choice(
            choices: 'NO\nYES',
            description: 'Ignore passed tests in previous run (run all)',
            name: 'IGNORE_PREVIOUS_RUN'
        )
        choice(
            choices: 'YES\nNo',
            description: 'Release run?',
            name: 'RELEASE_RUN'
        )
        string(
            defaultValue: '57',
            description: 'Major version like 70,60, etc',
            name: 'PILLAR_VERSION'
        )
        string(
            defaultValue: 'main',
            description: 'Tag/Branch for percona/percona-server-mongodb-operator repository',
            name: 'GIT_BRANCH')
        string(
            defaultValue: 'https://github.com/percona/percona-server-mongodb-operator',
            description: 'percona-server-mongodb-operator repository',
            name: 'GIT_REPO')
        string(
            defaultValue: 'latest',
            description: 'GKE kubernetes version',
            name: 'PLATFORM_VER')
        choice(
            choices: 'rapid\nstable\nregular',
            description: 'GKE release channel',
            name: 'GKE_RELEASE_CHANNEL')
        choice(
            choices: 'YES\nNO',
            description: 'Run tests in cluster wide mode',
            name: 'CLUSTER_WIDE')
        string(
            defaultValue: '',
            description: 'Operator image: perconalab/percona-server-mongodb-operator:main',
            name: 'OPERATOR_IMAGE')
        string(
            defaultValue: '',
            description: 'MONGOD image: perconalab/percona-server-mongodb-operator:main-mongod5.0',
            name: 'IMAGE_MONGOD')
        string(
            defaultValue: '',
            description: 'Backup image: perconalab/percona-server-mongodb-operator:main-backup',
            name: 'IMAGE_BACKUP')
        string(
            defaultValue: '',
            description: 'PMM client image: perconalab/pmm-client:dev-latest',
            name: 'IMAGE_PMM_CLIENT')
        string(
            defaultValue: '',
            description: 'PMM server image: perconalab/pmm-server:dev-latest',
            name: 'IMAGE_PMM_SERVER')
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '-1', artifactDaysToKeepStr: '-1', numToKeepStr: '30', artifactNumToKeepStr: '30'))
        skipDefaultCheckout()
        disableConcurrentBuilds()
    }
    stages {
        stage('Prepare node') {
            steps {
                echo "This is prepareNode tests stage"
                verifyParams()
                getImages()
                //prepareNode()
            }
        }
        stage('Docker Build and Push') {
            steps {
                echo "This is Build and Pus tests stage"
                //dockerBuildPush()
            }
        }
        stage('Init tests') {
            steps {
                echo "This is Init tests stage"
                //initTests()
            }
        }
        stage('Run Tests') {
            parallel {
                stage('cluster1') {
                    steps {
                        echo "This is Run tests stage"
                        //clusterRunner('cluster1')
                    }
                }
            }
        }
    }
    post {
        always {
            echo "This is post stage"

        }
    }
}

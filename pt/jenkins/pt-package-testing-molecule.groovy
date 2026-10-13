library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

// List of molecule scenarios under molecule/percona-toolkit/
// Must match the scenario directory names exactly.
def ptPackageTesting() {
    return [
        'min-amazon-2',
        'amazon-linux-2023',
        //'amazon-linux-2023-arm',
        //'centos-7',
        //'debian-10',
        'debian-12',
        //'debian-12-arm',
        'debian-13',
        //'debian-13-arm',
        //'ol-8',
        //'ol-9',
        //'rhel-8',
        //'rhel-8-arm',
        //'rhel-9',
        //'rhel-9-arm',
        'rhel-10',
        //'rhel-10-arm',
        //'rocky-linux-8',
        //'rocky-linux-8-arm',
        //'rocky-linux-9',
        //'rocky-linux-9-arm',
        //'ubuntu-bionic',
        //'ubuntu-focal',
        //'ubuntu-focal-arm',
        'ubuntu-jammy',
        //'ubuntu-jammy-arm',
        'ubuntu-noble',
        //'ubuntu-noble-arm',
        'ubuntu-resolute',
        //'ubuntu-resolute-arm'
    ]
}

List allOS = ptPackageTesting()

// AWS regions used by the percona-toolkit molecule scenarios.
// us-west-1 -> pxc-sourced AMIs ; us-west-2 -> rocky + ps-sourced AMIs (debian-13, rhel-10, resolute)
List awsRegions = ['us-west-1', 'us-west-2']

def moleculeParallelTestALL(allOS, operatingSystems, moleculeDir) {
    def tests = [:]
    allOS.each { os ->
        tests["${os}"] = {
            stage("${os}") {
                if (operatingSystems.contains(os)) {
                    sh """
                        . virtenv/bin/activate
                        cd ${moleculeDir}
                        molecule test -s ${os}
                    """
                } else {
                    echo "Skipping ${os} as it's not in operatingSystems"
                }
            }
        }
    }
    parallel tests
}

def deleteBuildInstances(awsRegions){
    // Run cleanup inside an explicit node so withCredentials/sh have a workspace
    // (hudson.FilePath) context even when the declarative `post` block runs
    // without the pipeline's top-level agent attached.
    node('min-bookworm-x64') {
        echo "All tests completed"

        def awsCredentials = [
                sshUserPrivateKey(
                    credentialsId: 'MOLECULE_AWS_PRIVATE_KEY',
                    keyFileVariable: 'MOLECULE_AWS_PRIVATE_KEY',
                    passphraseVariable: '',
                    usernameVariable: ''
                ),
                aws(
                    accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                    credentialsId: 'c42456e5-c28d-4962-b32c-b75d161bff27',
                    secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                )
        ]

        withCredentials(awsCredentials) {
            def jobName = env.JOB_NAME
            def BUILD_NUMBER = env.BUILD_NUMBER
            jobName.trim()

            echo "Fetched JOB_TO_RUN from environment: '${jobName}'"

            // percona-toolkit scenarios span more than one AWS region, so clean each.
            awsRegions.each { region ->
                echo "=== Cleaning up EC2 instances in ${region} for job-name: ${jobName}, build-number: ${BUILD_NUMBER} ==="

                sh """
                echo "=== EC2 Instances to be cleaned up in ${region} ==="
                aws ec2 describe-instances --region ${region} \\
                --filters "Name=tag:job-name,Values=${jobName}" "Name=tag:build-number,Values=${BUILD_NUMBER}" \\
                --query "Reservations[].Instances[].[InstanceId,Tags[?Key=='Name'].Value|[0],State.Name]" \\
                --output table || echo "No instances found in ${region} with job-name tag: ${jobName}"
                """

                def instanceIds = sh(
                    script: """
                    aws ec2 describe-instances --region ${region} \\
                    --filters "Name=tag:job-name,Values=${jobName}" "Name=tag:build-number,Values=${BUILD_NUMBER}" "Name=instance-state-name,Values=running" \\
                    --query "Reservations[].Instances[].InstanceId" \\
                    --output text
                    """,
                    returnStdout: true
                ).trim()

                if (instanceIds != null && !instanceIds.trim().isEmpty()) {
                    echo "Found instances to terminate in ${region}: ${instanceIds.trim()}"

                    sh """
                    echo "${instanceIds.trim()}" | xargs -r aws ec2 terminate-instances --region ${region} --instance-ids
                    """

                    sleep(30)

                    echo "Terminated instances in ${region}: ${instanceIds.trim()}"
                    echo "==========================================="
                    echo "Verification: Status of terminated instances in ${region}:"

                    sh """
                    sleep 5 && aws ec2 describe-instances --region ${region} --instance-ids ${instanceIds} --query "Reservations[].Instances[].[InstanceId,Tags[?Key=='Name'].Value|[0],State.Name]" --output table
                    """
                } else {
                    echo "No instances found to terminate in ${region}"
                }
            }
        }
    }
}

def loadEnvFile(envFilePath) {
    def envMap = []
    def envFileContent = readFile(file: envFilePath).trim().split('\n')
    envFileContent.each { line ->
        if (line && !line.startsWith('#')) {
            def parts = line.split('=')
            if (parts.length == 2) {
                envMap << "${parts[0].trim()}=${parts[1].trim()}"
            }
        }
    }
    return envMap
}

pipeline {
    agent {
        label 'min-bookworm-x64'
    }
    parameters {
        choice(
            name: 'product_to_test',
            choices: ['pt'],
            description: 'Product to test (Percona Toolkit)'
        )
        choice(
            name: 'action_to_test',
            choices: ['install', 'upgrade', 'with_products'],
            description: 'Action to test: install -> pt.yml, upgrade -> pt_upgrade.yml, with_products -> pt_with_products.yml'
        )
        choice(
            name: 'install_repo',
            choices: ['testing', 'main', 'experimental'],
            description: 'Repo to install percona-toolkit packages from'
        )
        choice(
            name: 'install_with',
            choices: ['ps80', 'ps84', 'ps57', 'pxc80', 'pxc57', 'upstream80', 'upstream57', 'psmdb60', 'psmdb50', 'psmdb44'],
            description: 'Only used when action_to_test = with_products: product installed alongside pt'
        )
        string(
            defaultValue: 'Percona-QA',
            description: 'Git account name',
            name: 'git_account',
            trim: false
        )
        string(
            defaultValue: 'master',
            description: 'Git branch name',
            name: 'git_branch',
            trim: false
        )
    }
    environment {
        product_to_test = "${params.product_to_test}"
        action_to_test = "${params.action_to_test}"
        install_repo = "${params.install_repo}"
        install_with = "${params.install_with}"
        TESTING_BRANCH = "${params.git_branch}"
        TESTING_GIT_ACCOUNT = "${params.git_account}"
    }
    options {
        withCredentials(moleculepxbJenkinsCreds())
        timeout(time: 6, unit: 'HOURS')
    }
    stages {
        stage('Set Build Name') {
            steps {
                script {
                    currentBuild.displayName = "${env.BUILD_NUMBER}-${product_to_test}-${action_to_test}-${install_repo}"
                }
            }
        }
        stage('Checkout') {
            steps {
                deleteDir()
                git poll: false, branch: "${params.git_branch}", url: "https://github.com/${params.git_account}/package-testing.git"
            }
        }
        stage('Prepare') {
            steps {
                script {
                    installMoleculeBookwormMysql()
                }
            }
        }
        stage('RUN TESTS') {
            steps {
                script {
                    if (action_to_test == 'install') {
                        sh """
                            echo PLAYBOOK_VAR="pt" > .env.ENV_VARS
                        """
                    }
                    else if (action_to_test == 'upgrade') {
                        sh """
                            echo PLAYBOOK_VAR="pt_upgrade" > .env.ENV_VARS
                        """
                    }
                    else if (action_to_test == 'with_products') {
                        sh """
                            echo PLAYBOOK_VAR="pt_with_products" > .env.ENV_VARS
                        """
                    }
                    else {
                        error("Unsupported action_to_test: ${action_to_test}")
                    }

                    sh """
                        echo IIT_BILLING_TAG="pt_package_testing" >> .env.ENV_VARS
                        echo install_repo="${install_repo}" >> .env.ENV_VARS
                        echo install_with="${install_with}" >> .env.ENV_VARS
                    """

                    def envMap = loadEnvFile('.env.ENV_VARS')

                    withEnv(envMap) {
                        moleculeParallelTestALL(allOS, ptPackageTesting(), "molecule/percona-toolkit/")
                    }
                }
            }
        }
    }
    post {
        always {
            deleteBuildInstances(awsRegions)
            echo "Pipeline completed."
        }
    }
}



library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

properties([
    parameters([
        [
            $class: 'ChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'Choose the product version to test: PS8.0 OR ps_lts_innovation',
            name: 'product_to_test',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: 'return ["ps_57", "ps_80", "ps_84", "ps_lts_innovation", "client_test"]'
                ]
            ]
        ],
        choice(
            choices: ['testing', 'main', 'experimental'],
            description: 'Choose the repo to install packages and run the tests',
            name: 'install_repo'
        ),
        string(
            defaultValue: 'https://github.com/Percona-QA/package-testing.git',
            description: 'repo name',
            name: 'git_repo',
            trim: false
        ),
        string(
            defaultValue: 'master',
            description: 'Branch name',
            name: 'git_branch',
            trim: false
        ),
        [
            $class: 'CascadeChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'Action To Test',
            name: 'action_to_test',
            referencedParameters: 'product_to_test',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: '''
                        if (product_to_test == "ps_57") {
                            return ["install", "upgrade", "major_upgrade_from", "kmip", "kms"]
                        }
                        else if (product_to_test == "ps_80" || product_to_test == "ps_84") {
                            return ["install", "upgrade", "major_upgrade_to", "kmip", "kms"]
                        }
                        else {
                            return ["install", "upgrade", "kmip", "kms"]
                        }
                    '''
                ]
            ]
        ],


        [
            $class: 'CascadeChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'OS Choice',
            name: 'OS',
            referencedParameters: 'product_to_test',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: '''
                        if (product_to_test == "ps_57") {
                            return ["debian-10", "centos-7", "oracle-8", "ubuntu-bionic", "ubuntu-focal", "amazon-linux-2", "ubuntu-jammy", "oracle-9", "debian-12"]
                        }
                        else if (product_to_test == "ps_80" || product_to_test == "ps_84") {
                            return ["debian-11", "debian-11-arm", "debian-12", "debian-12-arm", "oracle-8", "oracle-9", "rhel-9", "rhel-10", "rhel-8-arm", "rhel-9-arm", "rhel-10-arm", "ubuntu-jammy", "ubuntu-jammy-arm", "ubuntu-focal", "ubuntu-focal-arm", "ubuntu-noble", "ubuntu-noble-arm"]
                        }
                        else {
                            return ["Not Supported"]
                        }
                    '''
                ]
            ]
        ],
        [
            $class: 'CascadeChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'EOL version or Normal (only available for ps_57)',
            name: 'EOL',
            referencedParameters: 'product_to_test',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: '''
                        if (product_to_test == "ps_57") {
                            return ["yes", "no"]
                        }
                        else {
                            return ["no"]
                        }
                    '''
                ]
            ]
        ],
        choice(
            choices: ['yes', 'no'],
            description: 'check_warnings',
            name: 'check_warnings'
        ),
        choice(
            choices: ['yes', 'no'],
            description: 'Install MySQL Shell',
            name: 'install_mysql_shell'
        )
    ])
])

pipeline {
    agent {
        label 'min-bookworm-x64'
    }
    environment {
        product_to_test = "${params.product_to_test}"
        git_repo = "${params.git_repo}"
        install_repo = "${params.install_repo}"
        action_to_test  = "${params.action_to_test}"
        check_warnings = "${params.check_warnings}"
        install_mysql_shell = "${params.install_mysql_shell}"
        EOL="${params.EOL}"
    }
    options {
        withCredentials(moleculePdpsJenkinsCreds())
    }
        stages {
            stage('ECHO'){
                steps {
                    echo "product_to_test: ${product_to_test}"
                    echo "git_repo: ${git_repo}"
                    echo "install_repo: ${install_repo}"
                    echo "action_to_test: ${action_to_test}"
                    echo "check_warnings: ${check_warnings}"
                    echo "install_mysql_shell: ${install_mysql_shell}"
                    echo "EOL: ${EOL}"
                }
            }
        }
    post {
        always {
            echo "Pipeline completed."
        }
    }
}


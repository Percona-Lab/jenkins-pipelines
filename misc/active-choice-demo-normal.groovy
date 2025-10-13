
    library changelog: false, identifier: "lib@master", retriever: modernSCM([
        $class: 'GitSCMSource',
        remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
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
    }
    parameters {
        choice(
            choices: ['ps_57','ps_80','ps_84','ps_lts_innovation','client_test'],
            description: 'Choose the product version to test: PS8.0 OR ps_lts_innovatoin',
            name: 'product_to_test'
        )
        choice(
            choices: ['testing', 'main', 'experimental'],
            description: 'Choose the repo to install packages and run the tests',
            name: 'install_repo'
        )
        choice(
            name: "EOL",
            choices: ["yes", "no"],
            description: "EOL version or Normal"
        )
        string(
            defaultValue: 'https://github.com/Percona-QA/package-testing.git',
            description: 'repo name',
            name: 'git_repo',
            trim: false
        )
        string(
            defaultValue: 'master',
            description: 'Branch name',
            name: 'git_branch',
            trim: false
        )
        choice(
            choices: [
                'install',
                'upgrade',
                'major_upgrade_from',
                'major_upgrade_to',
                'kmip',
                'kms'
            ],
            description: 'Action To Test',
            name: 'action_to_test'
        )
        choice(
            choices: [
                "debian-11",
                "debian-11-arm",
                "debian-12",
                "debian-12-arm",
                "oracle-8",
                "oracle-9",
                "rhel-9",
                "rhel-10",
                "rhel-8-arm",
                "rhel-9-arm",
                "rhel-10-arm",
                "ubuntu-jammy",
                "ubuntu-jammy-arm",
                "ubuntu-focal",
                "ubuntu-focal-arm",
                "ubuntu-noble",
                "ubuntu-noble-arm"
            ],
            description: 'OS',
            name: 'OS'
        )


        choice(
            choices: [
                'yes',
                'no',
            ],
            description: 'check_warnings',
            name: 'check_warnings'
        )
        choice(
            choices: [
                'yes',
                'no'
            ],
            description: 'Install MySQL Shell',
            name: 'install_mysql_shell'
        )
    }
    options {
        withCredentials(moleculePdpsJenkinsCreds())
    }

        stages {
            stage('Echo Hello'){
                steps {
                    echo "Hello, this is a normal job"
                }
            }

            
        }
    }

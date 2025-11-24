def call(Map args = [:]) {
    def product_to_test  = args.get('product_to_test', '')
    def PS_RELEASE       = args.get('PS_RELEASE', '')
    def PS_VERSION_SHORT = args.get('PS_VERSION_SHORT', '')
    def PS_VERSION_SHORT_KEY = args.get('PS_VERSION_SHORT_KEY', '')
    def minitestNodes    = args.get('minitestNodes', [])
    def SLACKNOTIFY      = args.get('SLACKNOTIFY', '')
    def BRANCH           = args.get('BRANCH', '')
    def DOCKER_ACC       = args.get('DOCKER_ACC', '')
    def packageTestsClosure = args.get('packageTestsClosure', null)
    def dockerTestClosure = args.get('dockerTestClosure', null)

    echo "Starting post-success logic..."
    echo "PS_RELEASE: ${PS_RELEASE}"
    echo "PS_VERSION_SHORT_KEY: ${PS_VERSION_SHORT_KEY}"
    echo "PS_VERSION_SHORT: ${PS_VERSION_SHORT}"
    echo "Docker Account: ${DOCKER_ACC}"

    // Extract PS_REVISION from properties file
    def PS_REVISION = ''
    if (fileExists('test/percona-server-8.0.properties')) {
        PS_REVISION = sh(returnStdout: true, script: "grep REVISION test/percona-server-8.0.properties | awk -F '=' '{ print\$2 }'").trim()
        echo "Revision is: ${PS_REVISION}"
    } else {
        error "Properties file not found: test/percona-server-8.0.properties"
    }

    if (product_to_test == 'PS80') {
        echo "Running PS80-specific steps"
    } else if (product_to_test == 'PS84') {
        echo "Running PS84-specific steps"
    } else {
        echo "Running client test"
    }

    if ("${PS_VERSION_SHORT}") {
        echo "Executing MINITESTS as VALID VALUE: ${PS_VERSION_SHORT}"
        echo "Checking for GitHub Repo VERSIONS file changes..."

        withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'TOKEN')]) {
            sh """#!/bin/bash
                set -e -x
                git clone https://jenkins-pxc-cd:\${TOKEN}@github.com/Percona-QA/package-testing.git
                cd package-testing
                git config user.name "jenkins-pxc-cd"
                git config user.email "it+jenkins-pxc-cd@percona.com"
                echo "${PS_VERSION_SHORT} is the VALUE!!@!"
                export RELEASE_VER_VAL="${PS_VERSION_SHORT}"
                if [[ "\$RELEASE_VER_VAL" =~ ^PS8[0-9]{1}\$ ]]; then
                    echo "\$RELEASE_VER_VAL is a valid version"
                    OLD_REV=\$(grep ${PS_VERSION_SHORT}_REV VERSIONS | cut -d '=' -f2-)
                    OLD_VER=\$(grep ${PS_VERSION_SHORT}_VER VERSIONS | cut -d '=' -f2-)
                    sed -i s/${PS_VERSION_SHORT}_REV=\$OLD_REV/${PS_VERSION_SHORT}_REV='"'${PS_REVISION}'"'/g VERSIONS
                    sed -i s/${PS_VERSION_SHORT}_VER=\$OLD_VER/${PS_VERSION_SHORT}_VER='"'${PS_RELEASE}'"'/g VERSIONS
                else
                    echo "INVALID PS8_RELEASE_VERSION VALUE: \$RELEASE_VER_VAL"
                fi
                git diff
                if [[ -z \$(git diff) ]]; then
                    echo "No changes"
                else
                    echo "There are changes"
                    git add -A
                    git commit -m "Autocommit: add ${PS_REVISION} and ${PS_RELEASE} for ${PS_VERSION_SHORT} package testing VERSIONS file."
                    git remote set-url origin https://jenkins-pxc-cd:\${TOKEN}@github.com/Percona-QA/package-testing.git
                    git push origin testing-branch
                fi
            """
        }

        if (packageTestsClosure && dockerTestClosure) {
            parallel(
                "Start Minitests for PS": {
                    try {
                        packageTestsClosure(minitestNodes)
                        echo "Minitests completed successfully. Triggering next stages."

                        // Trigger PS package-testing job on another Jenkins
                        withCredentials([string(credentialsId: 'JNKPERCONA_PS80_TOKEN', variable: 'TOKEN')]) {
                            def jenkinsServerUrl = 'https://ps80.cd.percona.com'
                            def jobName = 'ps-package-testing-molecule'
                            def response = sh(script: """
                                curl -X POST \\
                                -u ${TOKEN} \\
                                "${jenkinsServerUrl}/job/${jobName}/buildWithParameters" \\
                                --data-urlencode "product_to_test=${product_to_test}" \\
                                --data-urlencode "install_repo=testing" \\
                                --data-urlencode "action_to_test=install" \\
                                --data-urlencode "check_warnings=yes" \\
                                --data-urlencode "install_mysql_shell=no"
                            """, returnStdout: true).trim()
                            echo "PS job triggered on ${jenkinsServerUrl}/job/${jobName}"
                            echo "Response: ${response}"
                        }

                        // Trigger GitHub workflow
                        echo "Trigger PMM_PS GitHub Actions Workflow"
                        withCredentials([string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GITHUB_API_TOKEN')]) {
                            sh """
                                curl -i -v -X POST \
                                -H "Accept: application/vnd.github.v3+json" \
                                -H "Authorization: token ${GITHUB_API_TOKEN}" \
                                "https://api.github.com/repos/Percona-Lab/qa-integration/actions/workflows/PMM_PS.yaml/dispatches" \
                                -d '{"ref":"main","inputs":{"ps_version":"${PS_RELEASE}"}}'
                            """
                        }

                    } catch (err) {
                        echo " Minitests block failed: ${err}"
                        currentBuild.result = 'FAILURE'
                        throw err
                    }
                },
                "Start Docker job": {
                    try {
                        dockerTestClosure()
                        echo "Docker images run successfully."
                    } catch (err) {
                        echo " Docker test block failed: ${err}"
                        currentBuild.result = 'FAILURE'
                        throw err
                    }
                }
            )
        } else {
            error "packageTestsClosure and dockerTestClosure must be provided"
        }

    } else {
        error "Skipping MINITESTS — invalid RELEASE VERSION FOR THIS JOB"
    }

    deleteDir()
}

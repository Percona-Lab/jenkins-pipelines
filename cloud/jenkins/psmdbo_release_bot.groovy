// PSMDB release bot: one pass over the release tracking issue in Percona-Lab/operator-release-bot.
// It reads ticked boxes and comment commands, starts the psmdbo-* release builds QA asked for and
// writes the results back to the issue. Its state lives on the bot-state branch of that repo, so a
// pass on any agent carries on where the last one stopped. The bot code and README are in that repo.
//
// The job lives in the release-bot folder, which holds its Jenkins API and Claude credentials.
// Set RELEASE to the version under test; an empty RELEASE pauses the bot.

pipeline {
    agent {
        label 'launcher-x64'
    }
    options {
        skipDefaultCheckout()
        disableConcurrentBuilds()
        timeout(time: 15, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '500'))
    }
    triggers {
        cron('H/3 * * * *')
    }
    parameters {
        string(name: 'RELEASE', defaultValue: '1.24.0', description: 'Release version, e.g. 1.24.0. Empty pauses the bot.')
        string(name: 'SINCE', defaultValue: '', description: 'Ignore builds started before this UTC date, e.g. 2026-11-20. Empty: all builds of the release branch.')
        string(name: 'MAINTAINERS', defaultValue: 'jvpasinatto,eleo007,valmiranogueira', description: 'GitHub logins allowed /accept-failure and /count-run besides repo maintainers.')
        booleanParam(name: 'DRY_RUN', defaultValue: false, description: 'Read only: no GitHub writes, no builds started.')
    }
    stages {
        stage('Bot pass') {
            when {
                expression { params.RELEASE?.trim() }
            }
            steps {
                withCredentials([
                    string(credentialsId: 'GITHUB_API_TOKEN', variable: 'GH_TOKEN'),
                    usernamePassword(credentialsId: 'PSMDBO_RELEASE_BOT_JENKINS_API', usernameVariable: 'JENKINS_USER', passwordVariable: 'JENKINS_TOKEN'),
                    string(credentialsId: 'PSMDBO_RELEASE_BOT_ANTHROPIC_KEY', variable: 'RELEASE_BOT_ANTHROPIC_API_KEY')
                ]) {
                    sh '''
                        set -e
                        # git asks this helper for the token, so it never lands in .git/config
                        helper='!f() { echo username=x-access-token; echo "password=$GH_TOKEN"; }; f'
                        repo=https://github.com/Percona-Lab/operator-release-bot.git
                        if [ -d bot/.git ]; then
                            git -C bot -c credential.helper= -c credential.helper="$helper" fetch -q origin main
                            git -C bot reset -q --hard FETCH_HEAD
                        else
                            rm -rf bot
                            git -c credential.helper= -c credential.helper="$helper" clone -q --branch main "$repo" bot
                        fi

                        set -- --version "$RELEASE" --sandbox Percona-Lab/operator-release-bot \
                            --state-dir ".state/$RELEASE" --maintainers "$MAINTAINERS"
                        if [ -n "$SINCE" ]; then set -- "$@" --since "$SINCE"; fi
                        if [ "$DRY_RUN" != true ]; then set -- "$@" --apply --trigger; fi

                        docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp \
                            -e GH_TOKEN -e JENKINS_USER -e JENKINS_TOKEN -e RELEASE_BOT_ANTHROPIC_API_KEY \
                            -v "$WORKSPACE/bot:/w" -w /w python:3.13 \
                            jenkins/run-once.sh "$@"
                    '''
                }
            }
        }
    }
}

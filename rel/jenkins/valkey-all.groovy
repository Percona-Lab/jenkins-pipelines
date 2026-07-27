library changelog: false, identifier: 'lib@hetzner', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

// trigger — return a closure (for parallel{}) that runs a downstream job and
// waits for it, propagating failure so a broken package/image fails the pipeline.
def trigger(String jobName, List jobParams) {
    return {
        build job: jobName, parameters: jobParams, wait: true, propagate: true
    }
}

pipeline {
    agent {
        label params.CLOUD == 'Hetzner' ? 'docker-x64-min' : 'micro-amazon'
    }
    parameters {
        choice(
            choices: ['Hetzner', 'AWS'],
            description: 'Cloud infra for all triggered jobs',
            name: 'CLOUD')
        choice(
            choices: ['testing', 'experimental'],
            description: 'Repo channel: packages are published here, Docker images and tests consume from it',
            name: 'CHANNEL')
        string(
            defaultValue: 'valkey-91',
            description: 'Repository every package publishes to (and the test stage installs from)',
            name: 'VALKEY_REPO')

        // --- What to build (tick to include) ---
        booleanParam(defaultValue: true, description: 'Package: percona-valkey (server)',        name: 'PKG_SERVER')
        booleanParam(defaultValue: true, description: 'Package: percona-valkey-json',            name: 'PKG_JSON')
        booleanParam(defaultValue: true, description: 'Package: percona-valkey-bloom',           name: 'PKG_BLOOM')
        booleanParam(defaultValue: true, description: 'Package: percona-valkey-search',          name: 'PKG_SEARCH')
        booleanParam(defaultValue: true, description: 'Package: percona-valkey-ldap',            name: 'PKG_LDAP')
        booleanParam(defaultValue: true, description: 'Package: percona-valkey-audit',           name: 'PKG_AUDIT')
        booleanParam(defaultValue: true, description: 'Package: percona-valkey-bundle',          name: 'PKG_BUNDLE')
        booleanParam(defaultValue: true, description: 'Docker image: valkey (server)',           name: 'IMG_VALKEY')
        booleanParam(defaultValue: true, description: 'Docker image: valkey-bundle',             name: 'IMG_BUNDLE')
        booleanParam(defaultValue: true, description: 'Docker image: valkey modules',            name: 'IMG_MODULE')
        booleanParam(defaultValue: true, description: 'Run the package test suite (final stage)', name: 'RUN_TESTS')

        // --- Versions / refs ---
        string(defaultValue: '9.1.1', description: 'Valkey server version (server RELEASE, Docker, tests)', name: 'VALKEY_VERSION')
        string(defaultValue: '9.1',   description: 'Upstream valkey-io/valkey tag/branch (server RELEASE)', name: 'GIT_BRANCH')
        string(defaultValue: '9.1.1', description: 'valkey-packaging branch (modules, bundle, Docker, tests)', name: 'PACKAGING_BRANCH')
        string(defaultValue: '1',     description: 'Package release number applied to every package', name: 'RELEASE')
        string(defaultValue: '1.0.2', description: 'valkey-json version',   name: 'VALKEY_JSON_VERSION')
        string(defaultValue: '1.0.1', description: 'valkey-bloom version',  name: 'VALKEY_BLOOM_VERSION')
        string(defaultValue: '1.2.0', description: 'valkey-search version', name: 'VALKEY_SEARCH_VERSION')
        string(defaultValue: '1.1.1', description: 'valkey-ldap version',   name: 'VALKEY_LDAP_VERSION')
        string(defaultValue: '0.2.2', description: 'valkey-audit version',  name: 'VALKEY_AUDIT_VERSION')
        string(defaultValue: '9.1.0', description: 'valkey-bundle version', name: 'VALKEY_BUNDLE_VERSION')
        string(defaultValue: 'ALL',   description: 'Platforms for the test stage (see hetzner-valkey-TESTING)', name: 'TEST_PLATFORMS')
    }
    options {
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '20'))
        timestamps()
    }
    stages {
        stage('Build packages') {
            when { expression { params.PKG_SERVER || params.PKG_JSON || params.PKG_BLOOM || params.PKG_SEARCH || params.PKG_LDAP || params.PKG_AUDIT || params.PKG_BUNDLE } }
            steps {
                script {
                    String cloud = params.CLOUD
                    String ch    = params.CHANNEL
                    String rel   = params.RELEASE
                    String pkgBr = params.PACKAGING_BRANCH
                    Map jobs = [:]
                    if (params.PKG_SERVER) {
                        jobs['server'] = trigger('hetzner-valkey-RELEASE', [
                            string(name: 'CLOUD',          value: cloud),
                            string(name: 'GIT_BRANCH',     value: params.GIT_BRANCH),
                            string(name: 'VALKEY_VERSION', value: params.VALKEY_VERSION),
                            string(name: 'VALKEY_RELEASE', value: rel),
                            string(name: 'VALKEY_REPO',    value: params.VALKEY_REPO),
                            string(name: 'COMPONENT',      value: ch),
                        ])
                    }
                    if (params.PKG_JSON) {
                        jobs['json'] = trigger('hetzner-valkey-json-RELEASE', [
                            string(name: 'CLOUD',               value: cloud),
                            string(name: 'PACKAGING_BRANCH',    value: pkgBr),
                            string(name: 'VALKEY_JSON_VERSION', value: params.VALKEY_JSON_VERSION),
                            string(name: 'VALKEY_JSON_RELEASE', value: rel),
                            string(name: 'VALKEY_JSON_REPO',    value: params.VALKEY_REPO),
                            string(name: 'COMPONENT',           value: ch),
                        ])
                    }
                    if (params.PKG_BLOOM) {
                        jobs['bloom'] = trigger('hetzner-valkey-bloom-RELEASE', [
                            string(name: 'CLOUD',                value: cloud),
                            string(name: 'PACKAGING_BRANCH',     value: pkgBr),
                            string(name: 'VALKEY_BLOOM_VERSION', value: params.VALKEY_BLOOM_VERSION),
                            string(name: 'VALKEY_BLOOM_RELEASE', value: rel),
                            string(name: 'VALKEY_BLOOM_REPO',    value: params.VALKEY_REPO),
                            string(name: 'COMPONENT',            value: ch),
                        ])
                    }
                    if (params.PKG_SEARCH) {
                        jobs['search'] = trigger('hetzner-valkey-search-RELEASE', [
                            string(name: 'CLOUD',                 value: cloud),
                            string(name: 'PACKAGING_BRANCH',      value: pkgBr),
                            string(name: 'VALKEY_SEARCH_VERSION', value: params.VALKEY_SEARCH_VERSION),
                            string(name: 'VALKEY_SEARCH_RELEASE', value: rel),
                            string(name: 'VALKEY_SEARCH_REPO',    value: params.VALKEY_REPO),
                            string(name: 'COMPONENT',             value: ch),
                        ])
                    }
                    if (params.PKG_LDAP) {
                        jobs['ldap'] = trigger('hetzner-valkey-ldap-RELEASE', [
                            string(name: 'CLOUD',               value: cloud),
                            string(name: 'VALKEY_LDAP_VERSION', value: params.VALKEY_LDAP_VERSION),
                            string(name: 'VALKEY_LDAP_RELEASE', value: rel),
                            string(name: 'VALKEY_LDAP_REPO',    value: params.VALKEY_REPO),
                            string(name: 'COMPONENT',           value: ch),
                        ])
                    }
                    if (params.PKG_AUDIT) {
                        jobs['audit'] = trigger('hetzner-valkey-audit-RELEASE', [
                            string(name: 'CLOUD',                value: cloud),
                            string(name: 'VALKEY_AUDIT_VERSION', value: params.VALKEY_AUDIT_VERSION),
                            string(name: 'VALKEY_AUDIT_RELEASE', value: rel),
                            string(name: 'VALKEY_AUDIT_REPO',    value: params.VALKEY_REPO),
                            string(name: 'COMPONENT',            value: ch),
                        ])
                    }
                    if (params.PKG_BUNDLE) {
                        jobs['bundle'] = trigger('hetzner-valkey-bundle-RELEASE', [
                            string(name: 'CLOUD',                 value: cloud),
                            string(name: 'PACKAGING_BRANCH',      value: pkgBr),
                            string(name: 'VALKEY_BUNDLE_VERSION', value: params.VALKEY_BUNDLE_VERSION),
                            string(name: 'VALKEY_BUNDLE_RELEASE', value: rel),
                            string(name: 'VALKEY_BUNDLE_REPO',    value: params.VALKEY_REPO),
                            string(name: 'COMPONENT',             value: ch),
                        ])
                    }
                    echo "Building packages: ${jobs.keySet().join(', ')}"
                    parallel(jobs)
                }
            }
        }
        stage('Build Docker images') {
            when { expression { params.IMG_VALKEY || params.IMG_BUNDLE || params.IMG_MODULE } }
            steps {
                script {
                    String cloud = params.CLOUD
                    String ch    = params.CHANNEL
                    String pkgBr = params.PACKAGING_BRANCH
                    Map jobs = [:]
                    if (params.IMG_VALKEY) {
                        jobs['valkey'] = trigger('hetzner-valkey-DOCKER', [
                            string(name: 'CLOUD',            value: cloud),
                            string(name: 'PACKAGING_BRANCH', value: pkgBr),
                            string(name: 'VALKEY_VERSION',   value: params.VALKEY_VERSION),
                            string(name: 'REPO_CHANNEL',     value: ch),
                        ])
                    }
                    if (params.IMG_BUNDLE) {
                        jobs['bundle'] = trigger('hetzner-valkey-bundle-DOCKER', [
                            string(name: 'CLOUD',            value: cloud),
                            string(name: 'PACKAGING_BRANCH', value: pkgBr),
                            string(name: 'VALKEY_VERSION',   value: params.VALKEY_VERSION),
                            string(name: 'REPO_CHANNEL',     value: ch),
                        ])
                    }
                    if (params.IMG_MODULE) {
                        jobs['module'] = trigger('hetzner-valkey-module-DOCKER', [
                            string(name: 'CLOUD',            value: cloud),
                            string(name: 'PACKAGING_BRANCH', value: pkgBr),
                            string(name: 'REPO_CHANNEL',     value: ch),
                        ])
                    }
                    echo "Building Docker images: ${jobs.keySet().join(', ')}"
                    parallel(jobs)
                }
            }
        }
        stage('Run tests') {
            when { expression { params.RUN_TESTS } }
            steps {
                build job: 'hetzner-valkey-TESTING', wait: true, propagate: true, parameters: [
                    string(name: 'CLOUD',            value: params.CLOUD),
                    string(name: 'VALKEY_VERSION',   value: params.VALKEY_VERSION),
                    string(name: 'PACKAGING_BRANCH', value: params.PACKAGING_BRANCH),
                    string(name: 'COMPONENT',        value: params.CHANNEL),
                    string(name: 'VALKEY_REPO',      value: params.VALKEY_REPO),
                    string(name: 'PLATFORMS',        value: params.TEST_PLATFORMS),
                ]
            }
        }
    }
    post {
        success {
            slackNotify("#releases-ci", "#00FF00", "[${JOB_NAME}]: full Valkey pipeline succeeded for ${params.VALKEY_VERSION} (${params.CHANNEL}) - [${BUILD_URL}]")
        }
        failure {
            slackNotify("#releases-ci", "#FF0000", "[${JOB_NAME}]: full Valkey pipeline failed for ${params.VALKEY_VERSION} (${params.CHANNEL}) - [${BUILD_URL}]")
        }
    }
}

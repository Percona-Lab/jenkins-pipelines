library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

List pro_nodes = [
                'ubuntu-noble',
                'ubuntu-jammy',
                'ubuntu-noble-arm',
                'ubuntu-jammy-arm',
                'debian-12',
                'debian-12-arm',
                'ol-9',
                'rhel-9',
                'rhel-9-arm',
                'amazon-linux-2023',
                'amazon-linux-2023-arm'
]   

List non_pro_nodes = [
                'ubuntu-noble',
                'ubuntu-jammy',
                'ubuntu-noble-arm',
                'ubuntu-jammy-arm',
                'ubuntu-focal',
                'debian-12',
                'debian-11',
                'debian-12-arm',
                'debian-11-arm',
                'ol-8',
                'ol-9',
                'rhel-8',
                'rhel-9',
                'amazon-linux-2023',
                'amazon-linux-2023-arm'
]

List pxc57_nodes = [
                'ubuntu-jammy',
                'ubuntu-focal',
                'debian-12',
                'debian-11',
                'ol-8',
                'ol-9'
]

List all_possible_nodes = (pro_nodes + non_pro_nodes).unique()

product_to_test = params.product_to_test

void runNodeBuild(String node_to_test) {

    if("${params.test_repo}" == "main"){
        test_type = "install"
        echo "${test_type}"
    }
    else{
        echo "${test_type}"
    }

    if (params.product_to_test == "pxc-innovation-lts" || params.product_to_test == "pxc57") {
        if (pro_repo == "yes") {
            echo "PRO is not supported for PXC-5.7 or PXC-innovation-lts testing"
        } else {
            echo "Normal testing"
            job = "pxc-package-testing"
            env.JOB_TO_RUN = "${job}"
            test_type = params.test_type

            build(
                job: "${job}",
                parameters: [
                    string(name: "product_to_test", value: params.product_to_test),
                    string(name: "node_to_test", value: node_to_test),
                    string(name: "test_repo", value: params.test_repo),
                    string(name: "test_type", value: "${test_type}"),
                    string(name: "pxc57_repo", value: params.pxc57_repo)
                ],
                propagate: true,
                wait: true
            )
        }   
    }
    else if (params.product_to_test == "pxc84" || params.product_to_test == "pxc80") {

        echo "Testing PXC-8.4 or PXC-8.0 PRO packages"

        if (pro_repo == "yes") {
            echo "Testing PRO packages"
            job = "pxc-package-testing-pro"
            env.JOB_TO_RUN = "${job}"
            test_type = params.test_type_pro

            build(
                job: "${job}",
                parameters: [
                    string(name: "product_to_test", value: params.product_to_test),
                    string(name: "node_to_test", value: node_to_test),
                    string(name: "test_repo", value: params.test_repo),
                    string(name: "test_type", value: "${test_type}"),
                    string(name: "git_repo", value: params.git_repo),
                    string(name: "BRANCH", value: params.BRANCH)
                ],
                propagate: true,
                wait: true
            )

        } else {
            echo "Testing Non Pro packages"
            job = "pxc-package-testing"
            env.JOB_TO_RUN = "${job}"
            test_type = params.test_type

            build(
                job: "${job}",
                parameters: [
                    string(name: "product_to_test", value: params.product_to_test),
                    string(name: "node_to_test", value: node_to_test),
                    string(name: "test_repo", value: params.test_repo),
                    string(name: "test_type", value: "${test_type}"),
                    string(name: "pxc57_repo", value: params.pxc57_repo),
                    string(name: "git_repo", value: params.git_repo),
                    string(name: "BRANCH", value: params.BRANCH)
                ],
                propagate: true,
                wait: true
            )

        }


    } else {
        error("Unsupported product_to_test for PRO testing: ${params.product_to_test}")
    }
    echo "inside runNodeBuild job_to_run is ${env.JOB_TO_RUN}"
}

pipeline {
    agent {
        label 'docker'
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '100'))
    }
    parameters {
        choice(
            name: 'product_to_test',
            choices: [
                'pxc84',
                'pxc80',
                'pxc57',
                'pxc-innovation-lts'
            ],
            description: 'PXC product_to_test to test'
        )

        choice(
            name: 'test_repo',
            choices: [
                'testing',
                'main',
                'experimental'
            ],
            description: 'Repo to install packages from'
        )

        choice(
            name: "pxc57_repo",
            choices: ["EOL","original","pxc57"],
            description: "PXC-5.7 packages are located in 2 repos: pxc-57 and original and both should be tested. Choose which repo to use for test."
        )

        choice(
            name: 'test_type',
            choices: [
                "install"
                ,"min_upgrade_pxc57_eol_main_to_eol_testing"
                ,"min_upgrade_pxc_80"
                ,"min_upgrade_pxc_84"
                ,"min_upgrade_pxc_innovation"
            ],
            description: 'Supports PXC57, PXC80, PXC84, PXC Innovation NON PRO Packages'
        )

        choice(
            name: 'test_type_pro',
            choices: [
                'install',
                'min_upgrade_pro_pro',
                'min_upgrade_nonpro_pro',
                'min_upgrade_pro_nonpro',
            ],
            description: 'Supports PXC80 and PXC84 PRO packages'
        )

        choice(
            name: 'pro_repo',
            choices: [
                'no',
                'yes'
            ],
            description: 'Set if PRO packages should be tested or not (PXC 80 and PXC 84)'
        )

        string(
            name: 'git_repo',
            defaultValue: "Percona-QA/package-testing",
            description: 'Git repository to use for testing'
        )
        
        string(
            name: 'BRANCH',
            defaultValue: 'master',
            description: 'Git branch to use for testing'
        )

    }

    stages {
        stage("Prepare") {
            steps {
                script {
                    if (params.pro_repo == "yes") {
                        currentBuild.displayName = "#${BUILD_NUMBER}-${product_to_test}-${params.test_repo}-pro=${params.pro_repo}-${params.test_type_pro}"
                    }else {
                        currentBuild.displayName = "#${BUILD_NUMBER}-${product_to_test}-${params.test_repo}-pro=${params.pro_repo}-${params.test_type}"
                    }
                }
            }
        }

        stage("Run parallel") {
            steps {
                script {

                    def selectedNodes
                    def jobType
                    def allPossibleNodes
                    
                    if (params.product_to_test == "pxc57") {
                        selectedNodes = pxc57_nodes
                        jobType = "PXC 5.7"
                        allPossibleNodes = pxc57_nodes
                    } else {
                        selectedNodes = (params.pro_repo == "yes") ? pro_nodes : non_pro_nodes
                        jobType = (params.pro_repo == "yes") ? "PRO" : "Non Pro"
                        allPossibleNodes = all_possible_nodes
                    }

                    echo "Testing ${selectedNodes.size()}/${all_possible_nodes.size()} nodes for ${jobType} job"
                    
                    def parallelStages = [:]
                    
                    all_possible_nodes.each { node ->
                        parallelStages[node] = {
                
                            stage("${node}") {
                                if (selectedNodes.contains(node)) {
                                    echo "Running ${jobType} tests for: ${node}"
                                    runNodeBuild(node)
                                } else {
                                    echo "SKIPPED: ${node} - not in ${jobType} job"
                                    echo "This OS is not part of the ${jobType} testing suite"
                                }
                            }
                        }
                    }
                    
                    parallel parallelStages
                }
            }
        }
    }

}



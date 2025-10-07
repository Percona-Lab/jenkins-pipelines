library changelog: false, identifier: 'lib@master', retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
]) _

List pro_pxc80 = [
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

List pro_pxc84 = [
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


List non_pro_pxc80 = [
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
                'rhel-8-arm',
                'rhel-9-arm'
]

List non_pro_pxc84 = [
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
                'rhel-10',
                'rhel-8-arm',
                'rhel-9-arm',
                'rhel-10-arm'
]

List pxc_innovation_lts = [
                'ubuntu-noble',
                'ubuntu-jammy',
                'ubuntu-noble-arm',
                'ubuntu-jammy-arm',
]

List pxc57_nodes = [
                'ubuntu-jammy',
                'ubuntu-focal',
                'debian-12',
                'debian-11',
                'ol-8',
                'ol-9'
]

List all_possible_nodes = (pro_pxc80 + pro_pxc84 + non_pro_pxc80 + non_pro_pxc84 + pxc_innovation_lts + pxc57_nodes).unique()

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
            job = "pxc-package-testing-test"
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

properties([
    parameters([
        [
            $class: 'ChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'PXC product to test',
            name: 'product_to_test',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: 'return ["pxc84", "pxc80", "pxc57", "pxc-innovation-lts"]'
                ]
            ]
        ],
        choice(
            name: 'test_repo',
            choices: ['testing', 'main', 'experimental'],
            description: 'Repo to install packages from'
        ),
        [
            $class: 'CascadeChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'PRO packages (only for PXC80/84)',
            name: 'pro_repo',
            referencedParameters: 'product_to_test',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: '''
                        if (product_to_test == "pxc80" || product_to_test == "pxc84") {
                            return ["no", "yes"]
                        }
                        return ["no"]
                    '''
                ]
            ]
        ],
        [
            $class: 'CascadeChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'PXC57 repo (only for PXC57)',
            name: 'pxc57_repo',
            referencedParameters: 'product_to_test',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: '''
                        if (product_to_test == "pxc57") {
                            return ["EOL", "original", "pxc57"]
                        }
                        return ["N/A"]
                    '''
                ]
            ]
        ],
        [
            $class: 'CascadeChoiceParameter',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'Test type',
            name: 'test_type',
            referencedParameters: 'product_to_test,pro_repo,pxc57_repo',
            script: [
                $class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: '''
                        def result = ["install"]
                        
                        // Debug - you can remove this later
                        // return ["DEBUG: product=" + product_to_test + " pro=" + pro_repo + " pxc57=" + pxc57_repo]
                        
                        if (product_to_test == "pxc57") {
                            if (pxc57_repo == "EOL") {
                                result.add("min_upgrade_pxc57_eol_main_to_eol_testing")
                            }
                        } 
                        else if (product_to_test == "pxc80") {
                            if (pro_repo == "yes") {
                                result = ["install", "min_upgrade_pro_pro", "min_upgrade_nonpro_pro", "min_upgrade_pro_nonpro"]
                            } else {
                                result.add("min_upgrade_pxc_80")
                            }
                        } 
                        else if (product_to_test == "pxc84") {
                            if (pro_repo == "yes") {
                                result = ["install", "min_upgrade_pro_pro", "min_upgrade_nonpro_pro", "min_upgrade_pro_nonpro"]
                            } else {
                                result.add("min_upgrade_pxc_84")
                            }
                        } 
                        else if (product_to_test == "pxc-innovation-lts") {
                            result.add("min_upgrade_pxc_innovation")
                        }
                        
                        return result
                    '''
                ]
            ]
        ],
        string(
            name: 'git_repo',
            defaultValue: 'Percona-QA/package-testing',
            description: 'Git repository to use for testing'
        ),
        string(
            name: 'BRANCH',
            defaultValue: 'master',
            description: 'Git branch to use for testing'
        )
    ])
])

pipeline {
    agent {
        label 'docker'
    }
    options {
        buildDiscarder(logRotator(numToKeepStr: '100'))
    }


    stages {

        stage('Show Configuration') {
            steps {
                script {
                    echo "=== Test Configuration ==="
                    echo "Product: ${params.product_to_test}"
                    echo "Test Repo: ${params.test_repo}"
                    echo "PRO Repo: ${params.pro_repo}"
                    
                    if (params.product_to_test == 'pxc57') {
                        echo "PXC57 Repo: ${params.pxc57_repo}"
                    }
                    
                    echo "Test Type: ${params.test_type}"
                    echo "Git Repo: ${params.git_repo}"
                    echo "Branch: ${params.BRANCH}"
                }
            }
        }


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
                    //def allPossibleNodes
                    
                    if (params.product_to_test == "pxc57") {
                        selectedNodes = pxc57_nodes
                        jobType = "PXC 5.7"
                        allPossibleNodes = all_possible_nodes
                    } else if (params.product_to_test == "pxc80") {
                        selectedNodes = (params.pro_repo == "yes") ? pro_pxc80 : non_pro_pxc80
                        jobType = (params.pro_repo == "yes") ? "PXC 8.0 PRO" : "PXC 8.0 Non Pro"
                        allPossibleNodes = all_possible_nodes
                    } else if (params.product_to_test == "pxc84") {
                        selectedNodes = (params.pro_repo == "yes") ? pro_pxc84 : non_pro_pxc84
                        jobType = (params.pro_repo == "yes") ? "PXC 8.4 PRO" : "PXC 8.4 Non Pro"
                        allPossibleNodes = all_possible_nodes
                    } else if (params.product_to_test == "pxc_innovation_lts") {
                        selectedNodes = pxc_innovation_lts
                        jobType = "PXC Innovation LTS"
                        allPossibleNodes = all_possible_nodes
                    } else {
                        error("Unsupported product_to_test: ${params.product_to_test}")
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



def call(operatingSystems, moleculeDir) {
  tests = [:]
  operatingSystems.each { os ->
   tests["${os}"] =  {
        stage("${os}") {
            if (
              ("${os}" == 'ubuntu-focal' && params.server_to_test == 'ms_innovation_lts') ||
              (params.scenario_to_test == 'kms' && params.server_to_test == 'ms-80')  ||
              (params.scenario_to_test == 'kmip' && params.server_to_test == 'ms-80') ||
              (params.product_to_test == 'pxb_80' && params.server_to_test == 'ps_innovation_lts') ||
              (params.product_to_test == 'pxb_80' && params.server_to_test == 'ms_innovation_lts') ||
              (params.product_to_test == 'pxb_innovation_lts' && params.server_to_test == 'ps-80') ||
              (params.product_to_test == 'pxb_innovation_lts' && params.server_to_test == 'ms-80') ||
              (params.scenario_to_test == 'kmip' && params.server_to_test == 'ms_innovation_lts') ||
              (params.scenario_to_test == 'kms' && params.server_to_test == 'ms_innovation_lts') ||
              (params.REPO_TYPE == 'PRO' && params.product_to_test == 'pxb_84' && "${os}" == 'debian-11' ) ||
              (params.REPO_TYPE == 'PRO' && params.product_to_test == 'pxb_84' && "${os}" == 'ubuntu-focal' ) ||
              (params.REPO_TYPE == 'PRO' && params.product_to_test == 'pxb_84' && "${os}" == 'rhel-8-arm' ) ||
              (params.REPO_TYPE == 'PRO' && params.product_to_test == 'pxb_84' && "${os}" == 'rhel-8' ) ||
              (params.REPO_TYPE == 'PRO' && params.product_to_test == 'pxb_84' && "${os}" == 'oracle-8' ) ||
              (params.server_to_test == 'ms-84' && params.product_to_test == 'pxb_84' && "${os}" == 'debian-11-arm' ) ||
              (params.server_to_test == 'ms-84' && params.product_to_test == 'pxb_84' && "${os}" == 'debian-12-arm' ) ||
              (params.server_to_test == 'ms-84' && params.product_to_test == 'pxb_84' && "${os}" == 'rhel-9-arm' ) ||
              (params.server_to_test == 'ms-84' && params.product_to_test == 'pxb_84' && "${os}" == 'rhel-8-arm' ) ||
              (params.server_to_test == 'ms-84' && params.product_to_test == 'pxb_84' && "${os}" == 'ubuntu-focal' ) ||
              (params.server_to_test == 'ms-84' && params.product_to_test == 'pxb_84' && "${os}" == 'ubuntu-jammy-arm' ) ||
              (params.server_to_test == 'ms-84' && params.product_to_test == 'pxb_84' && "${os}" == 'ubuntu-noble-arm' ) ||
              (params.product_to_test == 'pxb_80' && params.server_to_test == 'ms-80' && "${os}" == 'ubuntu-noble-arm' ) ||
              (params.product_to_test == 'pxb_80' && params.server_to_test == 'ms-80' && "${os}" == 'ubuntu-jammy-arm' ) ||
              (params.product_to_test == 'pxb_80' && params.server_to_test == 'ms-80' && "${os}" == 'debian-12-arm' ) ||
              (params.product_to_test == 'pxb_80' && params.server_to_test == 'ms-80' && "${os}" == 'debian-11-arm' ) ||
              (params.product_to_test == 'pxb_80' && params.server_to_test == 'ms-80' && "${os}" == 'rhel-8-arm' ) ||
              (params.product_to_test == 'pxb_80' && params.server_to_test == 'ms-80' && "${os}" == 'rhel-9-arm' ) ||
              (params.product_to_test == 'pxb_80' && params.server_to_test == 'ms-80' && "${os}" == 'ubuntu-noble' )
              ) {
                echo "OS is not supported for this test. Skipping this stage."
                // Optional: Use return to skip the rest of the stage
                return
            }
            else {
              sh """
                    . virtenv/bin/activate
                    cd ${moleculeDir}
                    molecule test -s ${os}
                """
            }
        }
      }
    }
  parallel tests
}

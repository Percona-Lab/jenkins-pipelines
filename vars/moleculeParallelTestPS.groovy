def call(operatingSystems, moleculeDir) {
  tests = [:]
  operatingSystems.each { os ->
   tests["${os}"] =  {
        stage("${os}") {
//            if (("${os}" == 'ubuntu-focal' && params.server_to_test == 'ms_innovation_lts') || (params.scenario_to_test == 'kms' && params.server_to_test == 'ms-80')  || (params.scenario_to_test == 'kmip' && params.server_to_test == 'ms-80') || (params.product_to_test == 'pxb_80' && params.server_to_test == 'ps_innovation_lts') || (params.product_to_test == 'pxb_80' && params.server_to_test == 'ms_innovation_lts') || (params.product_to_test == 'pxb_innovation_lts' && params.server_to_test == 'ps-80') || (params.product_to_test == 'pxb_innovation_lts' && params.server_to_test == 'ms-80') || (params.scenario_to_test == 'kmip' && params.server_to_test == 'ms_innovation_lts') || (params.scenario_to_test == 'kms' && params.server_to_test == 'ms_innovation_lts') ) {
//                echo "OS is not supported for this test. Skipping this stage."
                // Optional: Use return to skip the rest of the stage
//                return
//            }
//            else {
              sh """
                    . virtenv/bin/activate
                    cd ${moleculeDir}
                    molecule test -s ${os}
                """
//            }
        }
      }
    }
  parallel tests
}

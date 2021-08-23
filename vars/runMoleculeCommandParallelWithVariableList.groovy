def call(operatingSystems, moleculeDir, action, varList) {
  tests = [:]
  operatingSystems.each { os ->
   tests["${os}"] =  {
        stage("${os}") {
            script {
                moleculeExecuteActionWithVariableListAndScenario("${moleculeDir}", "${action}", "${os}", "${varList}")
            }
        }
      }
    }
  parallel tests
}

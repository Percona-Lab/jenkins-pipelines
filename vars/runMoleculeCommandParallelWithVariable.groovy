def call(operatingSystems, moleculeDir, action, varName, varValue) {
  tests = [:]
  operatingSystems.each { os ->
   tests["${os}"] =  {
        stage("${os}") {
            script {
                moleculeExecuteActionWithVariableAndScenario("${moleculeDir}", "${action}", "${os}", "${varName}", "${varValue}")
            }
        }
      }
    }
  parallel tests
}

def call(operatingSystems, moleculeDir, action) {
  tests = [:]
  operatingSystems.each { os ->
   tests["${os}"] =  {
        stage("${os}") {
            moleculeExecuteActionWithScenario(${moleculeDir}, ${action}, ${os})
        }
      }
    }
  parallel tests
}

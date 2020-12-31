def call(operatingSystems, moleculeDir, action, varName, varValue) {
  tests = [:]
  operatingSystems.each { os ->
  tests["${os}"] =  {
        sh """
            . virtenv/bin/activate
            cd ${moleculeDir}
            ${varName}=${varValue} molecule ${action} -s ${os}
        """
      }
    }
  parallel tests
}

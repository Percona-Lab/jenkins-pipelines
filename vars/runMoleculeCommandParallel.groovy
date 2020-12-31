def call(operatingSystems, moleculeDir, action) {
  tests = [:]
  operatingSystems.each { os ->
  tests["${os}"] =  {
        sh """
            . virtenv/bin/activate
            cd ${moleculeDir}
            molecule ${action} -s ${os}
        """
      }
    }
  parallel tests
}

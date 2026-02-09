def call(operatingSystems, moleculeDir, skipOS) {
  tests = [:]
  operatingSystems.each { os ->
   tests["${os}"] =  {
        stage("${os}") {
        if (skipOS.contains(os)) {
          echo "Skipping OS: ${os}"
        } else {
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

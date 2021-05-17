def call(operatingSystems, moleculeDir) {
  tests = [:]
  operatingSystems.each { os ->
   tests["${os}"] =  {
        stage("${os}") {
            sh """
                  . virtenv/bin/activate
                  cd ${moleculeDir}
                  molecule --version
                  ansible --version
                  molecule test -s ${os}
               """
        }
      }
    }
  parallel tests
}

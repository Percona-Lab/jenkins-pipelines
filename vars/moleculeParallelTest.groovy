def call(operatingSystems, moleculeDir) {
  tests = [:]
  operatingSystems.each { os ->
   tests["${os}"] =  {
        stage("${os}") {
            sh """
                  cd ${moleculeDir}
                  molecule test -s ${os}
               """
            junit "${moleculeDir}/molecule/${os}/report.xml"
        }
      }
    }
  parallel tests
}

def call(operatingSystems, moleculeDir, action) {
  writeFile file: 'molecule_ami_env.sh', text: libraryResource('psmdb/molecule_ami_env.sh')
  tests = [:]
  operatingSystems.each { os ->
   tests["${os}"] =  {
        stage("${os}") {
            sh """
                  . virtenv/bin/activate
                  source molecule_ami_env.sh
                  cd ${moleculeDir}
                  molecule ${action} -s ${os}
               """
        }
      }
    }
  parallel tests
}

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
                  #
                  python3 --version
                  python3 -m pip install --upgrade pip
                  python3 -m pip install --upgrade setuptools
                  python3 -m pip install --upgrade setuptools-rust
                  python3 -m pip install --upgrade PyYaml==5.3.1 molecule==3.3.0 testinfra pytest molecule-ec2==0.3 molecule[ansible] "ansible<10.0.0" "ansible-lint>=5.1.1,<6.0.0" boto3 boto
                  #
                  cd ${moleculeDir}
                  molecule test -s ${os}
               """
          }
        }
      }
    }
  parallel tests
}

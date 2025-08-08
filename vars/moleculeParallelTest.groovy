def call(operatingSystems, moleculeDir) {
  tests = [:]
  operatingSystems.each { os ->
   tests["${os}"] =  {
        stage("${os}") {
            sh """
                  . virtenv/bin/activate
                  cd ${moleculeDir}
                  export MOLECULE_AL2023_AMI=ami-01634322a170b5fd0
                  export MOLECULE_AL2023_ARM_AMI=ami-0804ef6ecd6b2f0fa
                  export MOLECULE_DEBIAN10_AMI=ami-0f5d8e2951e3f83a5
                  export MOLECULE_DEBIAN11_AMI=ami-02d0122d25353b012
                  export MOLECULE_DEBIAN12_AMI=ami-0dbd0b6509fe8f301
                  export MOLECULE_RHEL8_AMI=ami-0bed2b29c076e8607
                  export MOLECULE_RHEL8_ARM_AMI=ami-0f52717fc1f2b83ba
                  export MOLECULE_RHEL8_FIPS_AMI=ami-0eaff9f89a47b28e6
                  export MOLECULE_RHEL9_AMI=ami-0357fd8270bb3203e
                  export MOLECULE_RHEL9_ARM_AMI=ami-0c106e78a676dfa32
                  export MOLECULE_UFOCAL_AMI=ami-0a15226b1f7f23580
                  export MOLECULE_UFOCAL_ARM_AMI=ami-0aca42cb579fcb3ba
                  export MOLECULE_UFOCAL_PRO_AMI=ami-0e0a93493e27b76c9
                  export MOLECULE_UJAMMY_AMI=ami-0597e0308dc02ed24
                  export MOLECULE_UJAMMY_ARM_AMI=ami-0fdd2ebda3a620f3a
                  export MOLECULE_UJAMMY_PRO_AMI=ami-058168290d30b9c52
                  export MOLECULE_UNOBLE_AMI=ami-01c276c8e835125d1
                  export MOLECULE_UNOBLE_ARM_AMI=ami-094fa2edb4d70acfb
                  export MOLECULE_UNOBLE_PRO_AMI=ami-08c47e4b2806964ce
                  molecule test -s ${os}
               """
        }
      }
    }
  parallel tests
}

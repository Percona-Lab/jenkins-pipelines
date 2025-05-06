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
                  export MOLECULE_DEBIAN11_AMI=ami-05f9dcaa9ddb9a15e
                  export MOLECULE_DEBIAN12_AMI=ami-0b6edd8449255b799
                  export MOLECULE_RHEL8_AMI=ami-087c2c50437d0b80d
                  export MOLECULE_RHEL8_ARM_AMI=ami-0bb199dd39edd7d71
                  export MOLECULE_RHEL8_FIPS_AMI=ami-0eaff9f89a47b28e6
                  export MOLECULE_RHEL9_AMI=ami-04a616933df665b44
                  export MOLECULE_RHEL9_ARM_AMI=ami-0d8185e750f8dfbd0
                  export MOLECULE_UFOCAL_AMI=ami-09dd2e08d601bff67
                  export MOLECULE_UFOCAL_ARM_AMI=ami-0570c9d51831648fb
                  export MOLECULE_UFOCAL_PRO_AMI=ami-0e0a93493e27b76c9
                  export MOLECULE_UJAMMY_AMI=ami-0ee8244746ec5d6d4
                  export MOLECULE_UJAMMY_ARM_AMI=ami-07534e46c9116761b
                  export MOLECULE_UJAMMY_PRO_AMI=ami-058168290d30b9c52
                  export MOLECULE_UNOBLE_AMI=ami-0ea4e9f7a6f7c30c8
                  export MOLECULE_UNOBLE_ARM_AMI=ami-0d34f4864e58df827
                  export MOLECULE_UNOBLE_PRO_AMI=ami-08c47e4b2806964ce
                  molecule test -s ${os}
               """
        }
      }
    }
  parallel tests
}

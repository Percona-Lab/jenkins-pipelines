def call(moleculeDir, action, scenario) {
    sh """
        . virtenv/bin/activate
        cd ${moleculeDir}
        export ami_debian11_x86_64=ami-0c902832debf5f049
        export ami_debian12_x86_64=ami-09a2f0264769556f1
        export ami_debian13_x86_64=ami-0eb2a285617d18845
        export ami_ol8_x86_64=ami-058eb9eaa259563c0
        export ami_ol9_x86_64=ami-0514f056ab68c7863
        export ami_rhel8_x86_64=ami-0d38405c22c6dfaf3
        export ami_rhel9_x86_64=ami-0b6540c077ff41a81
        export ami_rhel10_x86_64=ami-015b77c74fad9f40d
        export ami_rocky8_x86_64=ami-0f99e90e01ec42b57
        export ami_rocky9_x86_64=ami-019aef84b4837f73d
        export ami_rocky10_x86_64=ami-09f9edbd6a2d85ba9
        export ami_ubuntu22_x86_64=ami-074dd8e8dac7651a5
        export ami_ubuntu24_x86_64=ami-0aad10862ade98f27
        export ami_debian11_arm64=ami-0d12d5e9cf10ddd9b
        export ami_debian12_arm64=ami-0ca65bfaeef7deb8f
        export ami_debian13_arm64=ami-07e718c51f8ef6ef1
        export ami_ol8_arm64=ami-02063adb64607f0f3
        export ami_ol9_arm64=ami-0fcd8b1be597c5fcd
        export ami_rhel8_arm64=ami-05796f88f5487ea38
        export ami_rhel9_arm64=ami-0ddb23de4a4be5a3c
        export ami_rhel10_arm64=ami-0ad16a1fbf63249be
        export ami_rocky8_arm64=ami-07212fece0fd77907
        export ami_rocky9_arm64=ami-02ef97e98cb13bbf6
        export ami_rocky10_arm64=ami-0ecc8cb2d8f5b6948
        export ami_ubuntu22_arm64=ami-01099d45fb386e13b
        export ami_ubuntu24_arm64=ami-0a96a698343e1007e
        export region=eu-central-1
        export vpc_subnet_id_aws1=subnet-0775d65ad1e9703bc
        export vpc_subnet_id_aws2=subnet-09947b46d69590c50
        export vpc_subnet_id_aws3=subnet-0fad4db6fdd8025b6
        export driver=ec2
        molecule ${action} -s ${scenario}
    """
}

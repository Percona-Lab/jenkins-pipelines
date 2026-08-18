def call() {
    return """
        export ami_debian11_x86_64=ami-06687054858616b40
        export ami_debian12_x86_64=ami-0fe0ff90aca4c9a3d
        export ami_debian13_x86_64=ami-00baf448a290e0604
        # Oracle Linux + Rocky Linux AMIs: resolved dynamically to the newest
        # factory-built base per os+major+arch (tag role=ppg-package-test) so they
        # auto-update on each rebake instead of being hand-pinned. boto3 (not the aws CLI):
        # the molecule agent has boto3 (installMoleculePython39) but not always the CLI.
        # Fail-closed: assign on its own line (a bare assignment propagates the
        # helper's non-zero exit; an inline 'export VAR=...' masks it as export's
        # status), then '|| exit 1' aborts the step rather than launch an empty image.
        _ppg_ami() { python3 -c "import sys,boto3,botocore.config as C; m,a,o=sys.argv[1],sys.argv[2],sys.argv[3]; i=sorted(boto3.client('ec2',region_name='eu-central-1',config=C.Config(retries={'max_attempts':8,'mode':'standard'})).describe_images(Owners=['self'],Filters=[{'Name':'tag:role','Values':['ppg-package-test']},{'Name':'tag:os','Values':[o]},{'Name':'tag:os_major','Values':[m]},{'Name':'tag:arch','Values':[a]},{'Name':'state','Values':['available']}])['Images'],key=lambda x:x['CreationDate']); sys.stdout.write(i[-1]['ImageId'] if i else ''); sys.exit(0 if i else 1)" "\$1" "\$2" "\$3"; }
        ami_ol8_x86_64=\$(_ppg_ami 8 x86_64 oraclelinux) || exit 1; export ami_ol8_x86_64
        ami_ol9_x86_64=\$(_ppg_ami 9 x86_64 oraclelinux) || exit 1; export ami_ol9_x86_64
        ami_ol10_x86_64=\$(_ppg_ami 10 x86_64 oraclelinux) || exit 1; export ami_ol10_x86_64
        export ami_rhel8_x86_64=ami-07a95227ea69ac5a7
        export ami_rhel9_x86_64=ami-035f430eefe0c383c
        export ami_rhel10_x86_64=ami-0ef84e5fc5f7d6606
        ami_rocky8_x86_64=\$(_ppg_ami 8 x86_64 rocky) || exit 1; export ami_rocky8_x86_64
        ami_rocky9_x86_64=\$(_ppg_ami 9 x86_64 rocky) || exit 1; export ami_rocky9_x86_64
        ami_rocky10_x86_64=\$(_ppg_ami 10 x86_64 rocky) || exit 1; export ami_rocky10_x86_64
        export ami_ubuntu22_x86_64=ami-0ae88d5843aab690d
        export ami_ubuntu24_x86_64=ami-04bc554a9635a77c8
        export ami_ubuntu26_x86_64=ami-0f60f7b735e5e576c
        export ami_debian11_arm64=ami-08e24dd64c14e3365
        export ami_debian12_arm64=ami-03737899470c20c63
        export ami_debian13_arm64=ami-009aa536d30f23947
        ami_ol8_arm64=\$(_ppg_ami 8 arm64 oraclelinux) || exit 1; export ami_ol8_arm64
        ami_ol9_arm64=\$(_ppg_ami 9 arm64 oraclelinux) || exit 1; export ami_ol9_arm64
        ami_ol10_arm64=\$(_ppg_ami 10 arm64 oraclelinux) || exit 1; export ami_ol10_arm64
        export ami_rhel8_arm64=ami-04621017594ba750b
        export ami_rhel9_arm64=ami-094c0b306a78f3e3a
        export ami_rhel10_arm64=ami-05d5565696a1fbcc5
        ami_rocky8_arm64=\$(_ppg_ami 8 arm64 rocky) || exit 1; export ami_rocky8_arm64
        ami_rocky9_arm64=\$(_ppg_ami 9 arm64 rocky) || exit 1; export ami_rocky9_arm64
        ami_rocky10_arm64=\$(_ppg_ami 10 arm64 rocky) || exit 1; export ami_rocky10_arm64
        export ami_ubuntu22_arm64=ami-002da4b711811778d
        export ami_ubuntu24_arm64=ami-0c355ec49d386672d
        export ami_ubuntu26_arm64=ami-0f89bfe113307ab25
        export region=eu-central-1
        export vpc_subnet_id_aws1=subnet-0775d65ad1e9703bc
        export vpc_subnet_id_aws2=subnet-09947b46d69590c50
        export vpc_subnet_id_aws3=subnet-0c72f5b07120e4805
        export driver=ec2
    """
}

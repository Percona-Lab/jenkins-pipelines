def call() {
    return """
        export ami_debian11_x86_64=ami-009e9420630204b5d
        export ami_debian12_x86_64=ami-09ca7bd4727fb280b
        export ami_debian13_x86_64=ami-0da1f66573556d917
        # Oracle Linux AMIs: resolved dynamically to the newest factory-built base
        # per major+arch (tag role=ppg-package-test) so they auto-update on each
        # rebake instead of being hand-pinned. boto3 (not the aws CLI):
        # the molecule agent has boto3 (installMoleculePython39) but not always the CLI.
        # Fail-closed: assign on its own line (a bare assignment propagates the
        # helper's non-zero exit; an inline 'export VAR=...' masks it as export's
        # status), then '|| exit 1' aborts the step rather than launch an empty image.
        _ppg_ol_ami() { python3 -c "import sys,boto3,botocore.config as C; m,a=sys.argv[1],sys.argv[2]; i=sorted(boto3.client('ec2',region_name='eu-central-1',config=C.Config(retries={'max_attempts':8,'mode':'standard'})).describe_images(Owners=['self'],Filters=[{'Name':'tag:role','Values':['ppg-package-test']},{'Name':'tag:os','Values':['oraclelinux']},{'Name':'tag:os_major','Values':[m]},{'Name':'tag:arch','Values':[a]},{'Name':'state','Values':['available']}])['Images'],key=lambda x:x['CreationDate']); sys.stdout.write(i[-1]['ImageId'] if i else ''); sys.exit(0 if i else 1)" "\$1" "\$2"; }
        ami_ol8_x86_64=\$(_ppg_ol_ami 8 x86_64) || exit 1; export ami_ol8_x86_64
        ami_ol9_x86_64=\$(_ppg_ol_ami 9 x86_64) || exit 1; export ami_ol9_x86_64
        ami_ol10_x86_64=\$(_ppg_ol_ami 10 x86_64) || exit 1; export ami_ol10_x86_64
        export ami_rhel8_x86_64=ami-0d38405c22c6dfaf3
        export ami_rhel9_x86_64=ami-01ea92f779cc1c71f
        export ami_rhel10_x86_64=ami-01777900cf626c469
        export ami_rocky8_x86_64=ami-0f99e90e01ec42b57
        export ami_rocky9_x86_64=ami-019aef84b4837f73d
        export ami_rocky10_x86_64=ami-09f9edbd6a2d85ba9
        export ami_ubuntu22_x86_64=ami-0b3a21dbff1fffb4c
        export ami_ubuntu24_x86_64=ami-0596cf3199908321b
        export ami_ubuntu26_x86_64=ami-051eaec1417c5d4ae
        export ami_debian11_arm64=ami-08bb051b83411b514
        export ami_debian12_arm64=ami-04991928175cf27a1
        export ami_debian13_arm64=ami-08241d277446b81d7
        ami_ol8_arm64=\$(_ppg_ol_ami 8 arm64) || exit 1; export ami_ol8_arm64
        ami_ol9_arm64=\$(_ppg_ol_ami 9 arm64) || exit 1; export ami_ol9_arm64
        ami_ol10_arm64=\$(_ppg_ol_ami 10 arm64) || exit 1; export ami_ol10_arm64
        export ami_rhel8_arm64=ami-05796f88f5487ea38
        export ami_rhel9_arm64=ami-024a0efb56778bc6b
        export ami_rhel10_arm64=ami-0ad16a1fbf63249be
        export ami_rocky8_arm64=ami-07212fece0fd77907
        export ami_rocky9_arm64=ami-02ef97e98cb13bbf6
        export ami_rocky10_arm64=ami-0ecc8cb2d8f5b6948
        export ami_ubuntu22_arm64=ami-0e61fa0475e5cdf7d
        export ami_ubuntu24_arm64=ami-0abed25eed793978d
        export ami_ubuntu26_arm64=ami-042f11a836094cda0
        export region=eu-central-1
        export vpc_subnet_id_aws1=subnet-0775d65ad1e9703bc
        export vpc_subnet_id_aws2=subnet-09947b46d69590c50
        export vpc_subnet_id_aws3=subnet-0fad4db6fdd8025b6
        export driver=ec2
    """
}

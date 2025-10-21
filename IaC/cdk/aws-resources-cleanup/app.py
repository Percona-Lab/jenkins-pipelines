#!/usr/bin/env python3
"""CDK app for AWS Resources Cleanup Lambda."""

import os
import aws_cdk as cdk
from stacks.resource_cleanup_stack import ResourceCleanupStack

app = cdk.App()

ResourceCleanupStack(
    app,
    "AWSResourcesCleanupStack",
    description="Comprehensive AWS resource cleanup: EC2, EKS, OpenShift infrastructure",
    env=cdk.Environment(
        account=os.getenv('CDK_DEFAULT_ACCOUNT'),
        region=os.getenv('CDK_DEFAULT_REGION', 'us-east-2')
    ),
    tags={
        "Project": "PlatformEngineering",
        "ManagedBy": "CDK",
        "iit-billing-tag": "resource-cleanup"
    }
)

app.synth()

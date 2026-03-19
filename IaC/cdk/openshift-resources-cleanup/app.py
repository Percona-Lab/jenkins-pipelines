#!/usr/bin/env python3
"""CDK app for OpenShift Cluster Cleanup Lambda."""

import os
import aws_cdk as cdk
from stacks.resource_cleanup_stack import ResourceCleanupStack

# Stack name (single source of truth)
STACK_NAME = "OpenShiftResourcesCleanupStack"

app = cdk.App()

ResourceCleanupStack(
    app,
    STACK_NAME,
    description="OpenShift cluster infrastructure cleanup for AWS",
    env=cdk.Environment(
        account=os.getenv('CDK_DEFAULT_ACCOUNT'),
        region=os.getenv('CDK_DEFAULT_REGION', 'us-east-2')
    ),
    tags={
        "Project": "PlatformEngineering",
        "ManagedBy": "CDK",
        "iit-billing-tag": "openshift-cleanup"
    }
)

app.synth()

# Remove unused cloudformations
import logging
import datetime
import boto3
import sys
from time import sleep
from botocore.exceptions import ClientError
from boto3.exceptions import Boto3Error


def isStackToTerminate(stack):
    tags = stack.tags
    tags_dict = {item['Key']: item['Value'] for item in tags}

    if 'iit-billing-tag' not in tags_dict.keys():
        return False
    if 'delete-cluster-after-hours' not in tags_dict.keys() and tags_dict['iit-billing-tag'] == 'jenkins-eks':
        return True

    stack_lifetime = float(tags_dict['delete-cluster-after-hours'])
    current_time = datetime.datetime.now().timestamp()
    creation_time = int(stack.creation_time.timestamp())

    if (current_time - creation_time) / 3600 > stack_lifetime + 1 and tags_dict['iit-billing-tag'] == 'jenkins-eks':
        return True
    return False

def get_cloudformation_to_terminate(aws_region):
    cf_client = boto3.resource('cloudformation')
    statuses = ['ROLLBACK_COMPLETE', 'CREATE_COMPLETE', 'UPDATE_COMPLETE']
    stacks_for_deletion = []
    cloudformation_stacks = [stack for stack in cf_client.stacks.all() if stack.stack_status in statuses]
    if not cloudformation_stacks:
        logging.info(f"There are no cloudformation_stacks in cloud")
        sys.exit("There are no cloudformation_stacks in cloud")

    for stack in cloudformation_stacks:
        if isStackToTerminate(stack):
            stacks_for_deletion.append(stack.name)

    if not stacks_for_deletion:
        logging.info(f"There are no stacks for deletion")
        sys.exit("There are no stacks for deletion")
    return stacks_for_deletion

def delete_cloudformation_stacks(cloudformation_stack):
    cf_client = boto3.client('cloudformation')
    try:
        logging.info(f"Removing cloudformation stack: {cloudformation_stack}")
        cf_client.delete_stack(StackName=cloudformation_stack)
    except Boto3Error as e:
        logging.error(f"Delete of stack failed with error: {e}")

def lambda_handler(event, context):
    aws_region = 'eu-west-3'

    logging.info(f"Searching for resources to remove in {aws_region}.")
    cloudformation_stacks = get_cloudformation_to_terminate(aws_region)
    for cloudformation_stack in cloudformation_stacks:
        logging.info(f"Deleting cloudformation stacks.")
        delete_cloudformation_stacks(cloudformation_stack)

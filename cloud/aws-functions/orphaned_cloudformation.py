# Remove unused cloudformations
import logging
import datetime
import boto3
from boto3.exceptions import Boto3Error
from botocore.exceptions import ClientError
from utils import get_regions_list
from time import sleep

def is_stack_to_terminate(stack):
    tags = stack.tags
    tags_dict = {item['Key']: item['Value'] for item in tags}

    if 'team' not in tags_dict.keys() or ('team' in tags_dict.keys() and tags_dict['team'] != 'cloud'):
        return False
    if 'delete-cluster-after-hours' not in tags_dict.keys():
        return True

    stack_lifetime = float(tags_dict['delete-cluster-after-hours'])
    current_time = datetime.datetime.now().timestamp()
    creation_time = int(stack.creation_time.timestamp())

    if (current_time - creation_time) / 3600 > stack_lifetime + 1:
        return True
    return False

def get_cloudformation_to_terminate(aws_region):
    cf_client = boto3.resource('cloudformation')
    statuses = ['ROLLBACK_COMPLETE', 'CREATE_COMPLETE', 'UPDATE_COMPLETE', 'DELETE_FAILED']
    stacks_for_deletion = []
    cloudformation_stacks = [stack for stack in cf_client.stacks.all() if stack.stack_status in statuses]
    if not cloudformation_stacks:
        logging.info(f"There are no cloudformation_stacks in cloud")

    for stack in cloudformation_stacks:
        if is_stack_to_terminate(stack):
            stacks_for_deletion.append(stack.name)

    if not stacks_for_deletion:
        logging.info(f"There are no stacks for deletion")
    return stacks_for_deletion

def delete_stack(stack_name):
    client = boto3.client('cloudformation')
    try:
        # Initiate the delete operation
        logging.info(f"Removing cloudformation stack: {stack_name}")
        response = client.delete_stack(StackName=stack_name)
        logging.info(f'Delete stack RESPONSE: {response}')
    except ClientError as e:
        logging.info(f"Error deleting stack: {e}")

def force_delete_stack(stack_name):
    client = boto3.client('cloudformation')
    try:
        # Get the list of stack resources
        resources = client.describe_stack_resources(StackName=stack_name)

        # Loop through each resource
        for resource in resources['StackResources']:

            resource_id = resource['PhysicalResourceId']
            resource_type = resource['ResourceType']
            try:
                # Attempt to delete each resource individually
                logging.info(f"Attempting to delete resource: {resource_id} of type: {resource_type}")
                response = client.delete_stack(StackName=resource_id)
                sleep(5)
            except ClientError as e:
                logging.info(f"Failed to delete resource: {resource_id}. Error: {e}")

        # Delete the stack itself
        delete_stack(stack_name)
        response_desc = client.describe_stacks(StackName=stack_name)
    except ClientError as e:
        logging.info(f"Error describing stack resources: {e}")


def lambda_handler(event, context):
    aws_regions = get_regions_list()

    for aws_region in aws_regions:
        logging.info(f"Searching for resources to remove in {aws_region}.")
        cloudformation_stacks = get_cloudformation_to_terminate(aws_region)

        for cloudformation_stack in cloudformation_stacks:
            try:
                logging.info(f"Deleting cloudformation stacks.")
                force_delete_stack(cloudformation_stack)

            except ClientError as e:
                logging.info(f"Failed to delete resource: {resource_id}. Error: {e}")
                continue

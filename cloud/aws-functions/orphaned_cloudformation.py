# Remove unused cloudformations
import logging
import datetime
import boto3
from boto3.exceptions import Boto3Error
from botocore.exceptions import ClientError
from utils import get_regions_list
from time import sleep

def is_stack_to_terminate(stack, aws_region):
    cf_client = boto3.client('cloudformation', region_name=aws_region)

    try:
        stack_desc = cf_client.describe_stacks(StackName=stack)['Stacks'][0]
        tags = stack_desc['Tags']

    except ClientError as e:
        print(e)
        return False

    tags_dict = {item['Key']: item['Value'] for item in tags}

    if 'team' not in tags_dict.keys() or ('team' in tags_dict.keys() and tags_dict['team'] != 'cloud'):
        return False
    if 'delete-cluster-after-hours' not in tags_dict.keys():
        return True

    stack_lifetime = float(tags_dict['delete-cluster-after-hours'])
    current_time = datetime.datetime.now().timestamp()


    creation_time = int(stack_desc['CreationTime'].timestamp())

    if (current_time - creation_time) / 3600 > stack_lifetime + 1:
        return True
    return False

def get_cloudformation_to_terminate(aws_region):
    cf_client = boto3.client('cloudformation', region_name=aws_region)

    stacks_for_deletion = []

    cloudformation_stacks = [stack['StackName'] for stack in cf_client.list_stacks(StackStatusFilter=['ROLLBACK_COMPLETE', 'CREATE_COMPLETE', 'UPDATE_COMPLETE', 'DELETE_FAILED'])['StackSummaries']]

    if not cloudformation_stacks:
        logging.info(f"There are no cloudformation_stacks in cloud")

    for stack in cloudformation_stacks:
        if is_stack_to_terminate(stack, aws_region):
            stacks_for_deletion.append(stack)

    if not stacks_for_deletion:
        logging.info(f"There are no stacks for deletion")
    return stacks_for_deletion

def delete_stack(stack_name, aws_region):
    cf_client = boto3.client('cloudformation', region_name=aws_region)
    try:
        # Initiate the delete operation add timeout
        logging.info(f"Removing cloudformation stack: {stack_name}")
        waiter_config = {
            'Delay': 30,       # Time (in seconds) to wait between attempts
            'MaxAttempts': 10  # Maximum number of attempts (30s * 40 = 1200s or 20 minutes)
        }
        waiter = cf_client.get_waiter('stack_delete_complete')
        print(f"Waiting for stack {stack_name} to be deleted...")
        response = cf_client.delete_stack(StackName=stack_name)
        waiter.wait(StackName=stack_name, WaiterConfig=waiter_config)
    except ClientError as e:
        logging.info(f"Error deleting stack: {e}")

def delete_stack_resources(stack_name, aws_region):
    cf_client = boto3.client('cloudformation', region_name=aws_region)
    iam_client = boto3.client('iam')

    try:
        resources = cf_client.describe_stack_resources(StackName=stack_name)
        for resource in resources['StackResources']:
            resource_id = resource['PhysicalResourceId']
            resource_type = resource['ResourceType']
            print(f'resource_type {resource_type} stack_name {stack_name}')
            try:
                print(f"Attempting to delete resource: {resource_id} of type: {resource_type}")
                if resource_type == 'AWS::IAM::Role':
                    iam_client.delete_role(RoleName=resource_id)
                elif resource_type == 'AWS::IAM::Policy':
                    iam_client.delete_policy(PolicyArn=resource_id)
                elif resource_type == 'AWS::IAM::InstanceProfile':
                    try:
                        response = iam_client.get_instance_profile(InstanceProfileName=resource_id)
                        roles = response['InstanceProfile']['Roles']
                        if roles:
                            for role in roles:
                                print(f"Role attached to instance profile {instance_profile_name}: {role['RoleName']}")
                                iam_client.remove_role_from_instance_profile(InstanceProfileName=resource_id, RoleName=role)
                        else:
                            print(f"No roles are attached to instance profile {instance_profile_name}.")
                    except iam_client.exceptions.NoSuchEntityException:
                        print(f"Instance profile {instance_profile_name} does not exist.")
                    except Exception as e:
                        print(f"An error occurred: {e}")
                    iam_client.delete_instance_profile(InstanceProfileName=resource_id)

                sleep(2)  # Sleep to avoid hitting rate limits
            except ClientError as e:
                print(f"Failed to delete resource: {resource_id}. Error: {e}")
    except ClientError as e:
        print(f"Error describing stack resources: {e}")

def lambda_handler(event, context):

    aws_regions = get_regions_list()

    for aws_region in aws_regions:
        logging.info(f"Searching for resources to remove in {aws_region}.")
        cloudformation_stacks = get_cloudformation_to_terminate(aws_region)

        for cloudformation_stack in cloudformation_stacks:
            try:
                logging.info(f"Deleting cloudformation stacks.")
                delete_stack_resources(cloudformation_stack, aws_region)
                delete_stack(cloudformation_stack, aws_region)
            except ClientError as e:
                logging.info(f"Failed to delete resource: {resource_id}. Error: {e}")
                continue

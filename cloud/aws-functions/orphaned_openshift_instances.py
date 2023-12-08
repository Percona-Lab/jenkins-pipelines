# Remove openshift resources.
import logging
import datetime
import boto3
import sys
from time import sleep
from botocore.exceptions import ClientError
from boto3.exceptions import Boto3Error


def is_instance_to_terminate(instance):
    tags = instance.tags
    tags_dict = {item['Key']: item['Value'] for item in tags}
    state = instance.state['Name']
    if 'team' not in tags_dict.keys() or ('team' in tags_dict.keys() and tags_dict['team'] != 'cloud') or state != 'running':
        return False

    if 'delete-cluster-after-hours' not in tags_dict.keys():
        return True

    instance_lifetime = float(tags_dict['delete-cluster-after-hours'])
    current_time = datetime.datetime.now().timestamp()
    creation_time = instance.launch_time.timestamp()

    if (current_time - creation_time) / 3600 > instance_lifetime:
        return True
    return False

def get_instances_to_terminate(aws_region):
    instances_for_deletion = []
    ec2 = boto3.resource('ec2', region_name=aws_region)
    instances = ec2.instances.all()
    if not instances:
        logging.info(f"There are no instances in cloud")
        sys.exit("There are no instances in cloud")
    for instance in instances:
        if is_instance_to_terminate(instance):
            instances_for_deletion.append(instance.id)

    if not instances_for_deletion:
        logging.info(f"There are no instances for deletion")
        sys.exit("There are instances for deletion")
    return instances_for_deletion

def delete_instance(aws_region, instance_id):
    ec2 = boto3.resource('ec2', region_name=aws_region)
    ec2.instances.filter(InstanceIds=[instance_id]).terminate()


def lambda_handler(event, context):
    aws_regions = ['eu-west-2','eu-west-3']

    for aws_region in aws_regions:
        logging.info(f"Searching for resources to remove in {aws_region}.")
        instances = get_instances_to_terminate(aws_region)

        for instance_id in instances:
            logging.info(f"Terminating {instance}")
            delete_instance(instance_id=instance_id, aws_region=aws_region)




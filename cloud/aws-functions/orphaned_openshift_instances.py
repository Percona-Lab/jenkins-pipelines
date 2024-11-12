# Remove openshift resources.
import logging
import datetime
import boto3
from utils import get_regions_list


def is_instance_to_terminate(instance):
    tags = instance.tags
    state = instance.state["Name"]

    if state != "running" or tags == None:
        return False

    tags_dict = {item["Key"]: item["Value"] for item in tags}

    if "team" not in tags_dict.keys() or tags_dict.get("team") != "cloud":
        return False

    if "delete-cluster-after-hours" not in tags_dict.keys():
        return True

    instance_lifetime = float(tags_dict["delete-cluster-after-hours"])
    current_time = datetime.datetime.now().timestamp()
    try:
        creation_time = int(tags_dict["creation-time"])
    except KeyError as e:
        return True
    except ValueError as e:
        return True

    if (current_time - creation_time) / 3600 > instance_lifetime:
        return True
    return False


def get_instances_to_terminate(aws_region):
    instances_for_deletion = []
    ec2 = boto3.resource("ec2", region_name=aws_region)
    instances = ec2.instances.all()
    if not instances:
        logging.info(f"There are no instances in region {aws_region}")

    for instance in instances:
        if is_instance_to_terminate(instance):
            instances_for_deletion.append(instance.id)

    if not instances_for_deletion:
        logging.info(f"There are no instances for deletion")
    return instances_for_deletion


def delete_instance(aws_region, instance_id):
    ec2 = boto3.resource("ec2", region_name=aws_region)
    ec2.instances.filter(InstanceIds=[instance_id]).terminate()


def lambda_handler(event, context):
    aws_regions = get_regions_list()

    for aws_region in aws_regions:
        logging.info(f"Searching for resources to remove in {aws_region}.")
        instances = get_instances_to_terminate(aws_region)

        for instance_id in instances:
            logging.info(f"Terminating {instance_id}")
            delete_instance(instance_id=instance_id, aws_region=aws_region)

# Remove elastic ip.
import logging
import datetime
import boto3
from utils import get_regions_list


def is_ip_to_release(client, ip):
    try:
        tags = ip["Tags"]
    except KeyError as e:
        print(f"There are no tags in the resource {ip['PublicIp']}")
        return False

    tags_dict = {item["Key"]: item["Value"] for item in tags}

    if "team" not in tags_dict.keys() or tags_dict.get("team") != "cloud":
        return False

    if "delete-cluster-after-hours" not in tags_dict.keys():
        return True
    try:
        creation_time = int(tags_dict["creation-time"])
    except KeyError as e:
        return False

    instance_lifetime = float(tags_dict["delete-cluster-after-hours"])
    current_time = datetime.datetime.now().timestamp()

    if (current_time - creation_time) / 3600 > instance_lifetime:
        return True

    return False


def get_ip_to_release(client, aws_region):
    ips_for_release = []
    try:
        ips = client.describe_addresses()["Addresses"]
    except Exception as e:
        logging.error(f"The ips can't be received because of the error {e}")
        
    if not ips:
        logging.info(f"There are no ips in region {aws_region}")
        return ips_for_release

    for ip in ips:
        if is_ip_to_release(client, ip):
            ips_for_release.append(ip["AllocationId"])

    if not ips_for_release:
        logging.info(f"There are no ips for release")
    return ips_for_release


def release_ip(client, aws_region, allocation_id):
    try:
        client.release_address(AllocationId=allocation_id)
        logging.info(
            f"Elastic IP with Allocation ID {allocation_id} has been successfully released."
        )
    except Exception as e:
        logging.error(f"Error releasing Elastic IP: {e}")


def lambda_handler(event, context):

    aws_regions = get_regions_list()

    for aws_region in aws_regions:
        ec2 = boto3.client("ec2", region_name=aws_region)
        logging.info(f"Searching for resources to remove in {aws_region}.")
        allocation_ids = get_ip_to_release(ec2, aws_region)
        for allocation_id in allocation_ids:
            release_ip(ec2, aws_region, allocation_id)

# Remove unsused
import logging
import datetime
import boto3
from boto3.exceptions import NoSuchEntityException
from utils import get_regions_list


def is_user_to_terminate(user_name):
    if 'openshift' in user_name:
        # поправить проверку
        return True
    return False

def get_user_for_deletion(aws_region):
    client = boto3.client('iam', aws_region)
    users_for_deletion = []

    users = client.list_users()
    for user in users['Users']:
        user_name=user['UserName']
        if is_user_to_terminate(user_name):
            users_for_deletion.append(user_name)
    if not users_for_deletion:
        logging.info(f"There are no users for deletion")
    return users_for_deletion

def delete_user(user_name, aws_region):
    client = boto3.client('iam', aws_region)
    try:
        client.delete_user(user_name)
    except NoSuchEntityException as e:
        logging.error(f"Delete of user failed with error: {e}")

def lambda_handler(event, context):
    aws_regions = get_regions_list()

    for aws_region in aws_regions:
        logging.info(f"Searching for resources to remove in {aws_region}.")
        users = get_user_for_deletion(aws_region)
        for user in users:
            logging.info(f"Deleting user {user}.")
            delete_user(user, aws_region)



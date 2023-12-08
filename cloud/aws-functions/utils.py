import boto3

def get_regions_list():
    client = boto3.client('ec2')
    return [region['RegionName'] for region in client.describe_regions()['Regions']]

# Remove unused vpcs and connected resources.
import logging
import datetime
import boto3
from time import sleep
from botocore.exceptions import ClientError
from boto3.exceptions import Boto3Error
from utils import get_regions_list


def is_vpc_to_terminate(vpc):
    tags = vpc.tags
    if tags == None:
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
    if (current_time - creation_time) / 3600 > instance_lifetime:
        return True
    return False


def get_vpcs_to_terminate(aws_region):
    vpcs_for_deletion = []
    ec2 = boto3.resource("ec2", region_name=aws_region)
    vpcs = list(ec2.vpcs.all())
    if not vpcs:
        logging.info(f"There are no vpcs in cloud")
    for vpc in vpcs:
        if is_vpc_to_terminate(vpc):
            vpcs_for_deletion.append(vpc.id)

    if not vpcs_for_deletion:
        logging.info(f"There are no vpcs for deletion")
    return vpcs_for_deletion


def delete_vpc_ep(aws_region, vpc_id):
    ec2_client = boto3.client("ec2", region_name=aws_region)
    for ep in ec2_client.describe_vpc_endpoints(
        Filters=[{"Name": "vpc-id", "Values": [vpc_id]}]
    )["VpcEndpoints"]:
        try:
            ec2_client.delete_vpc_endpoints(VpcEndpointIds=[ep["VpcEndpointId"]])
        except Boto3Error as e:
            logging.error(f"Deleting VPC endpoint with id {ep} failed with error: {e}")


def delete_load_balancers(aws_region, vpc_id):
    elb_client = boto3.client("elb", region_name=aws_region)
    elbv2_client = boto3.client("elbv2", region_name=aws_region)
    lb_names = [
        lb["LoadBalancerName"]
        for lb in elb_client.describe_load_balancers()["LoadBalancerDescriptions"]
        if lb["VPCId"] == vpc_id
    ]
    if lb_names:
        for lb_name in lb_names:
            try:
                logging.info(f"Deleting load balancer: {lb_name} for vpc id: {vpc_id}")
                elb_client.delete_load_balancer(LoadBalancerName=lb_name)
            except Boto3Error as e:
                logging.error(
                    f"Deleting load balancer {lb_name} failed with error: {e}"
                )

    lbv2_names = []

    responseV2 = elbv2_client.describe_load_balancers()["LoadBalancers"]
    for lbv2 in responseV2:
        if lbv2["VpcId"] == vpc_id:
            lbv2_names.append(lbv2["LoadBalancerArn"])
    if lbv2_names:
        for lbv2_name in lbv2_names:
            try:
                logging.info(
                    f"Deleting load balancer: {lbv2_name} for vpc id: {vpc_id}"
                )
                resp = elbv2_client.delete_load_balancer(LoadBalancerArn=lbv2_name)
            except Boto3Error as e:
                logging.error(
                    f"Deleting load balancer {lbv2_name} failed with error: {e}"
                )


def delete_nat_gateway(aws_region, vpc_id):
    ec2_client = boto3.client("ec2", region_name=aws_region)
    filters = [
        {
            "Name": "vpc-id",
            "Values": [
                f"{vpc_id}",
            ],
        },
    ]
    nat_gateway = ec2_client.describe_nat_gateways(Filters=filters)
    nat_gateway_ids = [nat["NatGatewayId"] for nat in nat_gateway["NatGateways"]]
    if nat_gateway_ids:
        for nat_gateway_id in nat_gateway_ids:
            logging.info(f"Deleting NAT gateway with id: {nat_gateway_id}")
            try:
                ec2_client.delete_nat_gateway(NatGatewayId=nat_gateway_id)
                wait_for_nat_gateway_delete(ec2_client, nat_gateway_id)
            except Boto3Error as e:
                logging.error(
                    f"Deleting NAT gateway with id {nat_gateway_id} failed with error: {e}"
                )


def delete_igw(ec2_resource, vpc_id):
    vpc_resource = ec2_resource.Vpc(vpc_id)
    igws = vpc_resource.internet_gateways.all()
    if igws:
        for igw in igws:
            try:
                logging.info(f"Detaching and deleting igw id: {igw.id}")
                igw.detach_from_vpc(VpcId=vpc_id)
                igw.delete()
            except Boto3Error as e:
                logging.info(f"Detaching or deleting igw failed with error: {e}")
                continue
            except ClientError as e:
                logging.info(f"Failed detach igw, will try again. The error was: {e}.")
                continue


def delete_subnets(ec2_resource, vpc_id):
    vpc_resource = ec2_resource.Vpc(vpc_id)
    subnets_all = vpc_resource.subnets.all()
    subnets = [ec2_resource.Subnet(subnet.id) for subnet in subnets_all]
    if subnets:
        for sub in subnets:
            for attempt in range(0, 10):
                logging.info(f"Removing subnet with id: {sub.id}. Attempt {attempt}/10")
                try:
                    logging.info(
                        f"Network interfaces {sub.network_interfaces.all()} for {sub}"
                    )
                    for interface in sub.network_interfaces.all():
                        for attempt in range(0, 10):
                            try:
                                interface.delete()
                            except ClientError as e:
                                logging.info(
                                    f"Failed to delete network interface, will try again. The error was: {e}. Sleeping 10 seconds"
                                )
                                sleep(10)
                                continue
                            break
                    sub.delete()
                except ClientError as e:
                    logging.info(
                        f"Failed to delete subnet, will try again. The error was: {e}. Sleeping 10 seconds"
                    )
                    sleep(10)
                    continue
                break


def delete_route_tables(ec2_resource, vpc_id):
    vpc_resource = ec2_resource.Vpc(vpc_id)
    route_tables = vpc_resource.route_tables.all()
    if route_tables:
        try:
            for route_table in route_tables:
                if (
                    route_table.associations_attribute
                    and route_table.associations_attribute[0]["Main"] == True
                ):
                    logging.info(
                        f"{route_table.id} is the main route table, skipping..."
                    )
                    continue
                logging.info(f"Removing route_table-id: {route_table.id}")
                table = ec2_resource.RouteTable(route_table.id)
                table.delete()
        except Boto3Error as e:
            logging.error(f"Delete of route table failed with error: {e}")
        except ClientError as e:
            logging.info(
                f"Failed detach route_table will try again. The error was: {e}."
            )


def delete_security_groups(security_groups):
    try:
        for security_group in security_groups:
            if security_group.group_name == "default":
                logging.info(
                    f"{security_group.id} is the default security group, skipping..."
                )
                continue
            if security_group.ip_permissions:
                logging.info(
                    f"Removing ingress rules for security group with id: {security_group.id}"
                )
                security_group.revoke_ingress(
                    IpPermissions=security_group.ip_permissions
                )
            if security_group.ip_permissions_egress:
                logging.info(
                    f"Removing egress rules for security group with id: {security_group.id}"
                )
                security_group.revoke_egress(
                    IpPermissions=security_group.ip_permissions_egress
                )

            logging.info(f"Removing security group with id: {security_group.id}")
            try:
                security_group.delete()
            except ClientError as e:
                logging.error(f"Deleting of security group failed with error: {e}")
                continue
    except Boto3Error as e:
        logging.error(f"Deleting of security group failed with error: {e}")


def wait_for_nat_gateway_delete(ec2, nat_gateway_id):
    timeout = 300  # 5 min
    attempt = 0
    sleep_time = 10
    attempts = timeout // sleep_time

    while attempt < attempts:
        try:
            status = ec2.describe_nat_gateways(NatGatewayIds=[nat_gateway_id])[
                "NatGateways"
            ][0]["State"]
        except ec2.exceptions.ResourceNotFoundException:
            logging.info(f"NAT gateway with id {nat_gateway_id} was not found.")
            break

        if status == "deleted":
            logging.info(
                f"NAT gateway with id {nat_gateway_id} was successfully deleted."
            )
            break
        logging.info(
            f"NAT gateway with id {nat_gateway_id} status is {status}. "
            f"Attempt {attempt}/{attempts}. Sleeping {sleep_time} seconds."
        )
        sleep(sleep_time)
        attempt += 1

    else:
        logging.error(
            f"NAT gateway with id {nat_gateway_id} was not deleted in {timeout} seconds."
        )


def terminate_vpc(vpc_id, aws_region):
    ec2_resource = boto3.resource("ec2", region_name=aws_region)

    logging.info(f"Deleting load balancers for VPC {vpc_id}.")
    delete_load_balancers(aws_region, vpc_id)

    logging.info(f"Deleting NAT gateway for VPC {vpc_id}.")
    delete_nat_gateway(aws_region, vpc_id)

    logging.info(f"Deleting endpoints for VPC {vpc_id}.")
    delete_vpc_ep(aws_region, vpc_id)
    sleep(30)
    logging.info(f"Deleting internet gateway for VPC {vpc_id}.")
    delete_igw(ec2_resource, vpc_id)

    logging.info(f"Deleting subnets for VPC {vpc_id}.")
    delete_subnets(ec2_resource, vpc_id)

    logging.info(f"Deleting route tables for VPC {vpc_id}.")
    delete_route_tables(ec2_resource, vpc_id)

    logging.info(f"Deleting security groups for VPC {vpc_id}.")

    vpc_resource = ec2_resource.Vpc(vpc_id)
    security_groups = vpc_resource.security_groups.all()
    attempt = 0
    while security_groups and attempt < 5:
        delete_security_groups(security_groups)
        security_groups = vpc_resource.security_groups.all()
        attempt += 1

    logging.info(f"Deleting VPC {vpc_id}.")
    for attempt in range(0, 10):
        logging.info(f"Deleting VPC {vpc_id}.. Attempt {attempt}/10")
        try:
            ec2_resource.Vpc(vpc_id).delete()
        except ClientError as e:
            logging.info(
                f"Failed to delete vpc, will try again. The error was: {e}. Sleeping 10 seconds"
            )
            sleep(10)
            continue
        break


def lambda_handler(event, context):
    aws_regions = get_regions_list()

    for aws_region in aws_regions:
        logging.info(f"Searching for resources to remove in {aws_region}.")
        vpcs = get_vpcs_to_terminate(aws_region)

        for vpc in vpcs:
            logging.info(f"Deleting all resources and VPC.")
            response = terminate_vpc(vpc, aws_region)
            logging.info(f"Failed to delete vpc. The error was: {response}")
            continue

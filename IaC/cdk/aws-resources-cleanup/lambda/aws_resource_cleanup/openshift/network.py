"""OpenShift network resources cleanup."""

import boto3
from botocore.exceptions import ClientError
from ..models.config import DRY_RUN
from ..utils import get_logger

logger = get_logger()


def delete_nat_gateways(infra_id: str, region: str):
    """Delete NAT gateways for OpenShift cluster."""
    try:
        ec2 = boto3.client("ec2", region_name=region)
        nat_gws = ec2.describe_nat_gateways(
            Filters=[
                {"Name": "tag:kubernetes.io/cluster/" + infra_id, "Values": ["owned"]},
                {"Name": "state", "Values": ["available", "pending"]},
            ]
        )["NatGateways"]

        for nat in nat_gws:
            if DRY_RUN:
                logger.info(
                    f"[DRY-RUN] Would DELETE nat_gateway {nat['NatGatewayId']} for cluster {infra_id}"
                )
            else:
                ec2.delete_nat_gateway(NatGatewayId=nat["NatGatewayId"])
                logger.info(
                    f"DELETE nat_gateway {nat['NatGatewayId']} for cluster {infra_id}"
                )

    except Exception as e:
        logger.error(f"Error deleting NAT gateways: {e}")


def release_elastic_ips(infra_id: str, region: str):
    """Release Elastic IPs for OpenShift cluster."""
    try:
        ec2 = boto3.client("ec2", region_name=region)
        eips = ec2.describe_addresses(
            Filters=[
                {"Name": "tag:kubernetes.io/cluster/" + infra_id, "Values": ["owned"]}
            ]
        )["Addresses"]

        for eip in eips:
            if "AllocationId" in eip:
                if DRY_RUN:
                    logger.info(
                        f"[DRY-RUN] Would DELETE elastic_ip {eip['AllocationId']} for cluster {infra_id}"
                    )
                else:
                    try:
                        ec2.release_address(AllocationId=eip["AllocationId"])
                        logger.info(
                            f"DELETE elastic_ip {eip['AllocationId']} for cluster {infra_id}"
                        )
                    except ClientError:
                        pass  # May already be released

    except Exception as e:
        logger.error(f"Error releasing EIPs: {e}")


def cleanup_network_interfaces(vpc_id: str, region: str):
    """Clean up orphaned network interfaces."""
    try:
        ec2 = boto3.client("ec2", region_name=region)
        enis = ec2.describe_network_interfaces(
            Filters=[
                {"Name": "vpc-id", "Values": [vpc_id]},
                {"Name": "status", "Values": ["available"]},
            ]
        )["NetworkInterfaces"]

        for eni in enis:
            if DRY_RUN:
                logger.info(
                    f"[DRY-RUN] Would DELETE network_interface {eni['NetworkInterfaceId']} in vpc {vpc_id}"
                )
            else:
                try:
                    ec2.delete_network_interface(
                        NetworkInterfaceId=eni["NetworkInterfaceId"]
                    )
                    logger.info(
                        f"DELETE network_interface {eni['NetworkInterfaceId']} in vpc {vpc_id}"
                    )
                except ClientError:
                    pass  # May already be deleted

    except Exception as e:
        logger.error(f"Error cleaning up ENIs: {e}")


def delete_vpc_endpoints(vpc_id: str, region: str):
    """Delete VPC endpoints."""
    try:
        ec2 = boto3.client("ec2", region_name=region)
        endpoints = ec2.describe_vpc_endpoints(
            Filters=[{"Name": "vpc-id", "Values": [vpc_id]}]
        )["VpcEndpoints"]

        for endpoint in endpoints:
            if DRY_RUN:
                logger.info(
                    f"[DRY-RUN] Would DELETE vpc_endpoint {endpoint['VpcEndpointId']} in vpc {vpc_id}"
                )
            else:
                try:
                    ec2.delete_vpc_endpoints(VpcEndpointIds=[endpoint["VpcEndpointId"]])
                    logger.info(
                        f"DELETE vpc_endpoint {endpoint['VpcEndpointId']} in vpc {vpc_id}"
                    )
                except ClientError:
                    pass

    except Exception as e:
        logger.error(f"Error deleting VPC endpoints: {e}")


def delete_security_groups(vpc_id: str, region: str):
    """Delete security groups with dependency handling."""
    try:
        ec2 = boto3.client("ec2", region_name=region)
        sgs = ec2.describe_security_groups(
            Filters=[{"Name": "vpc-id", "Values": [vpc_id]}]
        )["SecurityGroups"]

        # First pass: remove all ingress rules to break circular dependencies
        for sg in sgs:
            if sg["GroupName"] == "default":
                continue
            try:
                if sg.get("IpPermissions"):
                    if not DRY_RUN:
                        ec2.revoke_security_group_ingress(
                            GroupId=sg["GroupId"], IpPermissions=sg["IpPermissions"]
                        )
            except ClientError:
                pass

        # Second pass: delete security groups
        for sg in sgs:
            if sg["GroupName"] == "default":
                continue
            if DRY_RUN:
                logger.info(
                    f"[DRY-RUN] Would DELETE security_group {sg['GroupId']} in vpc {vpc_id}"
                )
            else:
                try:
                    ec2.delete_security_group(GroupId=sg["GroupId"])
                    logger.info(
                        f"DELETE security_group {sg['GroupId']} in vpc {vpc_id}"
                    )
                except ClientError:
                    pass

    except Exception as e:
        logger.error(f"Error deleting security groups: {e}")


def delete_subnets(vpc_id: str, region: str):
    """Delete subnets."""
    try:
        ec2 = boto3.client("ec2", region_name=region)
        subnets = ec2.describe_subnets(
            Filters=[{"Name": "vpc-id", "Values": [vpc_id]}]
        )["Subnets"]

        for subnet in subnets:
            if DRY_RUN:
                logger.info(
                    f"[DRY-RUN] Would DELETE subnet {subnet['SubnetId']} in vpc {vpc_id}"
                )
            else:
                try:
                    ec2.delete_subnet(SubnetId=subnet["SubnetId"])
                    logger.info(f"DELETE subnet {subnet['SubnetId']} in vpc {vpc_id}")
                except ClientError:
                    pass

    except Exception as e:
        logger.error(f"Error deleting subnets: {e}")


def delete_route_tables(vpc_id: str, region: str):
    """Delete route tables."""
    try:
        ec2 = boto3.client("ec2", region_name=region)
        rts = ec2.describe_route_tables(
            Filters=[{"Name": "vpc-id", "Values": [vpc_id]}]
        )["RouteTables"]

        for rt in rts:
            # Skip main route table
            is_main = any(
                assoc.get("Main", False) for assoc in rt.get("Associations", [])
            )
            if is_main:
                continue

            if DRY_RUN:
                logger.info(
                    f"[DRY-RUN] Would DELETE route_table {rt['RouteTableId']} in vpc {vpc_id}"
                )
            else:
                try:
                    ec2.delete_route_table(RouteTableId=rt["RouteTableId"])
                    logger.info(
                        f"DELETE route_table {rt['RouteTableId']} in vpc {vpc_id}"
                    )
                except ClientError:
                    pass

    except Exception as e:
        logger.error(f"Error deleting route tables: {e}")


def delete_internet_gateway(vpc_id: str, region: str):
    """Detach and delete internet gateway."""
    try:
        ec2 = boto3.client("ec2", region_name=region)
        igws = ec2.describe_internet_gateways(
            Filters=[{"Name": "attachment.vpc-id", "Values": [vpc_id]}]
        )["InternetGateways"]

        for igw in igws:
            if DRY_RUN:
                logger.info(
                    f"[DRY-RUN] Would DELETE internet_gateway {igw['InternetGatewayId']} in vpc {vpc_id}"
                )
            else:
                try:
                    ec2.detach_internet_gateway(
                        InternetGatewayId=igw["InternetGatewayId"], VpcId=vpc_id
                    )
                    ec2.delete_internet_gateway(
                        InternetGatewayId=igw["InternetGatewayId"]
                    )
                    logger.info(
                        f"DELETE internet_gateway {igw['InternetGatewayId']} in vpc {vpc_id}"
                    )
                except ClientError:
                    pass

    except Exception as e:
        logger.error(f"Error deleting IGW: {e}")


def delete_vpc(vpc_id: str, region: str):
    """Delete VPC."""
    try:
        ec2 = boto3.client("ec2", region_name=region)
        if DRY_RUN:
            logger.info(f"[DRY-RUN] Would DELETE vpc {vpc_id} in {region}")
        else:
            try:
                ec2.delete_vpc(VpcId=vpc_id)
                logger.info(f"DELETE vpc {vpc_id} in {region}")
            except ClientError:
                pass

    except Exception as e:
        logger.error(f"Error deleting VPC: {e}")

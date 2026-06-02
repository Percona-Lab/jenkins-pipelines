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
                    "Would DELETE nat_gateway",
                    extra={
                        "dry_run": True,
                        "nat_gateway_id": nat["NatGatewayId"],
                        "infra_id": infra_id,
                    },
                )
            else:
                ec2.delete_nat_gateway(NatGatewayId=nat["NatGatewayId"])
                logger.info(
                    "DELETE nat_gateway",
                    extra={"nat_gateway_id": nat["NatGatewayId"], "infra_id": infra_id},
                )

    except Exception as e:
        logger.error("Error deleting NAT gateways", extra={"error": str(e)})


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
                        "Would DELETE elastic_ip",
                        extra={
                            "dry_run": True,
                            "allocation_id": eip["AllocationId"],
                            "infra_id": infra_id,
                        },
                    )
                else:
                    try:
                        ec2.release_address(AllocationId=eip["AllocationId"])
                        logger.info(
                            "DELETE elastic_ip",
                            extra={
                                "allocation_id": eip["AllocationId"],
                                "infra_id": infra_id,
                            },
                        )
                    except ClientError:
                        pass  # May already be released

    except Exception as e:
        logger.error("Error releasing EIPs", extra={"error": str(e)})


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
                    "Would DELETE network_interface",
                    extra={
                        "dry_run": True,
                        "network_interface_id": eni["NetworkInterfaceId"],
                        "vpc_id": vpc_id,
                    },
                )
            else:
                try:
                    ec2.delete_network_interface(
                        NetworkInterfaceId=eni["NetworkInterfaceId"]
                    )
                    logger.info(
                        "DELETE network_interface",
                        extra={
                            "network_interface_id": eni["NetworkInterfaceId"],
                            "vpc_id": vpc_id,
                        },
                    )
                except ClientError:
                    pass  # May already be deleted

    except Exception as e:
        logger.error("Error cleaning up ENIs", extra={"error": str(e)})


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
                    "Would DELETE vpc_endpoint",
                    extra={
                        "dry_run": True,
                        "vpc_endpoint_id": endpoint["VpcEndpointId"],
                        "vpc_id": vpc_id,
                    },
                )
            else:
                try:
                    ec2.delete_vpc_endpoints(VpcEndpointIds=[endpoint["VpcEndpointId"]])
                    logger.info(
                        "DELETE vpc_endpoint",
                        extra={
                            "vpc_endpoint_id": endpoint["VpcEndpointId"],
                            "vpc_id": vpc_id,
                        },
                    )
                except ClientError:
                    pass

    except Exception as e:
        logger.error("Error deleting VPC endpoints", extra={"error": str(e)})


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
                    "Would DELETE security_group",
                    extra={
                        "dry_run": True,
                        "security_group_id": sg["GroupId"],
                        "vpc_id": vpc_id,
                    },
                )
            else:
                try:
                    ec2.delete_security_group(GroupId=sg["GroupId"])
                    logger.info(
                        "DELETE security_group",
                        extra={"security_group_id": sg["GroupId"], "vpc_id": vpc_id},
                    )
                except ClientError:
                    pass

    except Exception as e:
        logger.error("Error deleting security groups", extra={"error": str(e)})


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
                    "Would DELETE subnet",
                    extra={
                        "dry_run": True,
                        "subnet_id": subnet["SubnetId"],
                        "vpc_id": vpc_id,
                    },
                )
            else:
                try:
                    ec2.delete_subnet(SubnetId=subnet["SubnetId"])
                    logger.info(
                        "DELETE subnet",
                        extra={"subnet_id": subnet["SubnetId"], "vpc_id": vpc_id},
                    )
                except ClientError:
                    pass

    except Exception as e:
        logger.error("Error deleting subnets", extra={"error": str(e)})


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
                    "Would DELETE route_table",
                    extra={
                        "dry_run": True,
                        "route_table_id": rt["RouteTableId"],
                        "vpc_id": vpc_id,
                    },
                )
            else:
                try:
                    ec2.delete_route_table(RouteTableId=rt["RouteTableId"])
                    logger.info(
                        "DELETE route_table",
                        extra={"route_table_id": rt["RouteTableId"], "vpc_id": vpc_id},
                    )
                except ClientError:
                    pass

    except Exception as e:
        logger.error("Error deleting route tables", extra={"error": str(e)})


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
                    "Would DELETE internet_gateway",
                    extra={
                        "dry_run": True,
                        "internet_gateway_id": igw["InternetGatewayId"],
                        "vpc_id": vpc_id,
                    },
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
                        "DELETE internet_gateway",
                        extra={
                            "internet_gateway_id": igw["InternetGatewayId"],
                            "vpc_id": vpc_id,
                        },
                    )
                except ClientError:
                    pass

    except Exception as e:
        logger.error("Error deleting IGW", extra={"error": str(e)})


def delete_vpc(vpc_id: str, region: str) -> bool:
    """
    Delete VPC.

    Returns:
        True if VPC was deleted successfully
        False if VPC still has dependencies
    """
    try:
        ec2 = boto3.client("ec2", region_name=region)
        if DRY_RUN:
            logger.info(
                "Would DELETE vpc",
                extra={"dry_run": True, "vpc_id": vpc_id, "region": region},
            )
            return True  # In DRY_RUN, assume success
        else:
            try:
                ec2.delete_vpc(VpcId=vpc_id)
                logger.info("DELETE vpc", extra={"vpc_id": vpc_id, "region": region})
                return True
            except ClientError as e:
                error_code = e.response.get("Error", {}).get("Code", "")
                if error_code == "DependencyViolation":
                    logger.info(
                        "VPC still has dependencies, cannot delete yet",
                        extra={"vpc_id": vpc_id, "error_code": error_code},
                    )
                    return False
                else:
                    # Other errors (permissions, etc.) should be logged
                    logger.error(
                        "Error deleting VPC",
                        extra={
                            "vpc_id": vpc_id,
                            "error": str(e),
                            "error_code": error_code,
                        },
                    )
                    return False

    except Exception as e:
        logger.error("Unexpected error deleting VPC", extra={"error": str(e)})
        return False

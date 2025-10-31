"""OpenShift cluster comprehensive cleanup."""

from .detection import detect_openshift_infra_id
from .compute import delete_load_balancers
from .network import (
    delete_nat_gateways,
    release_elastic_ips,
    cleanup_network_interfaces,
    delete_vpc_endpoints,
    delete_security_groups,
    delete_subnets,
    delete_route_tables,
    delete_internet_gateway,
    delete_vpc,
)
from .dns import cleanup_route53_records
from .storage import cleanup_s3_state
from .orchestrator import destroy_openshift_cluster

__all__ = [
    "detect_openshift_infra_id",
    "delete_load_balancers",
    "delete_nat_gateways",
    "release_elastic_ips",
    "cleanup_network_interfaces",
    "delete_vpc_endpoints",
    "delete_security_groups",
    "delete_subnets",
    "delete_route_tables",
    "delete_internet_gateway",
    "delete_vpc",
    "cleanup_route53_records",
    "cleanup_s3_state",
    "destroy_openshift_cluster",
]

"""Logging configuration using AWS Lambda Powertools."""

import os

from aws_lambda_powertools import Logger

# Read log level from environment (default to INFO)
LOG_LEVEL = os.environ.get("LOG_LEVEL", "INFO").upper()

# Set up Powertools logger with service name
# This provides structured logging with automatic Lambda context injection
logger = Logger(
    service="aws-resource-cleanup",
    level=LOG_LEVEL,
)


def get_logger():
    """Get the configured logger instance.

    Returns Powertools Logger with:
    - Structured JSON logging
    - Automatic Lambda context (request_id, function_name, etc.)
    - CloudWatch Logs Insights ready
    """
    return logger

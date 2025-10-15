"""Logging configuration."""

import logging
import os

# Read log level from environment (default to INFO)
LOG_LEVEL = os.environ.get("LOG_LEVEL", "INFO").upper()

# Map string level to logging constant
LEVEL_MAPPING = {
    "DEBUG": logging.DEBUG,
    "INFO": logging.INFO,
    "WARNING": logging.WARNING,
    "ERROR": logging.ERROR,
}

# Set up logger
logger = logging.getLogger()
logger.setLevel(LEVEL_MAPPING.get(LOG_LEVEL, logging.INFO))


def get_logger():
    """Get the configured logger instance."""
    return logger

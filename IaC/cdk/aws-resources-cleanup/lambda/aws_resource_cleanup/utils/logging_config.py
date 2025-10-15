"""Logging configuration."""

import logging

# Set up logger
logger = logging.getLogger()
logger.setLevel(logging.INFO)


def get_logger():
    """Get the configured logger instance."""
    return logger

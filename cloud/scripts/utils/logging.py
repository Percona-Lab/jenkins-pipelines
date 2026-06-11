import logging
import os
import sys


SUCCESS = 25
RESET = "\033[0m"

COLORS = {
    logging.DEBUG: "\033[36m",
    logging.INFO: "\033[94m",
    logging.WARNING: "\033[33m",
    logging.ERROR: "\033[31m",
    logging.CRITICAL: "\033[1;31m",
    SUCCESS: "\033[32m",
}


def add_success_level():
    logging.addLevelName(SUCCESS, "SUCCESS")

    def success(self, msg, *args, **kwargs):
        if self.isEnabledFor(SUCCESS):
            self._log(SUCCESS, msg, args, **kwargs)

    logging.Logger.success = success
    logging.SUCCESS = SUCCESS


class ColorFormatter(logging.Formatter):
    def __init__(self, *args, use_color=False, **kwargs):
        super().__init__(*args, **kwargs)
        self.use_color = use_color

    def format(self, record):
        message = super().format(record)

        if not self.use_color:
            return message

        color = COLORS.get(record.levelno)
        return f"{color}{message}{RESET}" if color else message


def should_use_color():
    if os.environ.get("NO_COLOR"):
        return False

    if os.environ.get("FORCE_COLOR"):
        return True

    return sys.stdout.isatty()


def infer_log_level(msg):
    if msg.startswith(("ERROR:", "FAILED:")):
        return logging.ERROR

    if msg.startswith(("WARNING:", "WARN:")):
        return logging.WARNING

    if msg.startswith(("SUCCESS:", "OK:")):
        return SUCCESS

    return logging.INFO


def setup_stdout_logging(logger, level=None):
    add_success_level()

    log_level = getattr(logging, (level or "").upper(), logging.ERROR)

    handler = logging.StreamHandler(sys.stdout)
    handler.setLevel(log_level)
    handler.setFormatter(
        ColorFormatter(
            "[%(asctime)s] %(message)s",
            "%Y-%m-%d %H:%M:%S",
            use_color=should_use_color(),
        )
    )

    logger.handlers.clear()
    logger.propagate = False
    logger.setLevel(logging.DEBUG)
    logger.addHandler(handler)


def log_lines(logger, text, default_level=logging.DEBUG):
    if isinstance(text, bytes):
        text = text.decode(errors="replace")

    for line in text.rstrip().splitlines():
        logger.log(infer_log_level(line) if default_level is None else infer_log_level(line) if line.startswith(("ERROR:", "FAILED:", "WARNING:", "WARN:", "SUCCESS:", "OK:")) else default_level, line)


def log_message(logger, msg, level=None):
    logger.log(level or infer_log_level(msg), msg)

"""
Path-safety helpers for handling filenames received from remote peers.

Filenames that arrive inside a clipboard "files" payload are fully
attacker-controllable (the sender — or anyone able to inject a message — picks
the dictionary keys). Writing them directly under a download directory allows a
path-traversal / arbitrary-file-write attack (e.g. a key of
"../../.bashrc"). These helpers reduce any incoming name to a single, safe
filename component that always stays inside the chosen directory.
"""

import os
import re

# Characters that are illegal or problematic in filenames across platforms.
_ILLEGAL_CHARS = re.compile(r'[<>:"/\\|?*\x00-\x1f]')

_FALLBACK_NAME = "downloaded_file"


def safe_download_filename(filename) -> str:
    """
    Reduce an untrusted filename to a safe, single-component basename.

    - Strips any directory components (defeats "../" / absolute paths).
    - Removes illegal/control characters.
    - Rejects the special "." and ".." names.
    - Falls back to a generic name when nothing safe remains.

    Returns a non-empty filename guaranteed to contain no path separators.
    """
    name = "" if filename is None else str(filename)

    # Drop everything except the final path component, handling both separators.
    name = name.replace("\\", "/").split("/")[-1]
    name = os.path.basename(name)

    # Remove illegal/control characters, surrounding whitespace, and trailing
    # dots/spaces (problematic on Windows). Leading dots are kept so legitimate
    # dotfiles like ".bashrc" survive.
    name = _ILLEGAL_CHARS.sub("_", name).strip().rstrip(". ")

    if not name or name in {".", ".."}:
        return _FALLBACK_NAME

    return name

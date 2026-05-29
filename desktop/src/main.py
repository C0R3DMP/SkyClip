#!/usr/bin/env python3

# SkyClip - Self-hosted, end-to-end encrypted clipboard sync
# Repository: https://github.com/C0R3DMP/SkyClip
# License: GPL-3.0

from core.application import Application


class Main:
    def __init__(self):
        Application().run()

def main():
    Main()

if __name__ == "__main__":
    main()

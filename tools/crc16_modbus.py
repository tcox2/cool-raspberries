#!/usr/bin/env python3
"""Calculate this protocol's CRC-16/MODBUS and print its on-wire byte order."""

from __future__ import annotations

import argparse


def crc16_modbus(data: bytes) -> int:
    crc = 0xFFFF
    for octet in data:
        crc ^= octet
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("hex_bytes", nargs="+", help="hex octets, e.g. 7A 7A 21")
    args = parser.parse_args()
    data = bytes(int(item, 16) for item in args.hex_bytes)
    crc = crc16_modbus(data)
    print(f"{crc:04X}")
    print(f"wire bytes: {crc >> 8:02X} {crc & 0xFF:02X}")


if __name__ == "__main__":
    main()


import unittest

from tools.crc16_modbus import crc16_modbus


class CrcTests(unittest.TestCase):
    def test_standard_modbus_check_value(self) -> None:
        self.assertEqual(crc16_modbus(b"123456789"), 0x4B37)

    def test_keepalive_prefix(self) -> None:
        prefix = bytes.fromhex("7A 7A 21 D5 0C 00 00 AB 0A 0A")
        self.assertEqual(crc16_modbus(prefix), 0xFCF9)


if __name__ == "__main__":
    unittest.main()

"""Checks Huginn's Ventoy update against Handheld Forge's installer on a disk image file.
Run with HUGINN_FORGE_PATH pointing at a Handheld-Forge checkout."""

from __future__ import annotations

import importlib.machinery
import importlib.util
import os
import sys
import tempfile
import unittest
from pathlib import Path

FORGE = os.environ["HUGINN_FORGE_PATH"]
sys.path.insert(0, FORGE)

from handheld_forge import ventoy  # noqa: E402
from handheld_forge.blockdev import FileDisk  # noqa: E402
from tests.test_drive_tools import ventoy_files  # noqa: E402

loader = importlib.machinery.SourceFileLoader("drivetool", str(Path(__file__).with_name("huginn-drivetool")))
spec = importlib.util.spec_from_loader("drivetool", loader)
drivetool = importlib.util.module_from_spec(spec)
loader.exec_module(drivetool)
drivetool.emit = lambda **m: None


class UpdateTest(unittest.TestCase):
    def run_case(self, secure: bool) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            d = FileDisk(Path(tmp) / "drive.img", blocks=4 * 2097152)
            ventoy.install(d, ventoy_files(), log=lambda m: None, secure_boot=secure)
            data, efi = ventoy.detect(d)
            uuid = d.read(0, 1)[384:400]
            marker = b"HUGINN-ISO-DATA".ljust(512, b"!")
            d.write(data.start + 50_000, marker)

            drivetool.update(d, ventoy_files())

            self.assertEqual(marker, d.read(data.start + 50_000, 1))
            self.assertEqual(uuid, d.read(0, 1)[384:400])
            self.assertEqual(b"\x55\xaa", d.read(0, 1)[510:512])
            again = ventoy.detect(d)
            self.assertIsNotNone(again)
            img = ventoy.read_efi(d, again[1])
            self.assertEqual(secure, ventoy.secure_boot_enabled(img))
            self.assertEqual(drivetool.inspect(d)["ventoy"], True)
            d.close()

    def test_update_keeps_images_secure_boot_on(self):
        self.run_case(True)

    def test_update_keeps_images_secure_boot_off(self):
        self.run_case(False)

    def test_update_refuses_plain_drive(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = FileDisk(Path(tmp) / "drive.img", blocks=2 * 2097152)
            with self.assertRaises(drivetool.RawWriteError):
                drivetool.update(d, ventoy_files())
            self.assertEqual({"ventoy": False}, drivetool.inspect(d))
            d.close()


if __name__ == "__main__":
    unittest.main()

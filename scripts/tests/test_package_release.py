import contextlib
import importlib.util
import io
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
spec = importlib.util.spec_from_file_location(
    "package_release", Path(__file__).resolve().parents[1] / "package-release.py"
)
package = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package)


class PackageReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.metadata = {
            "artifactType": {"type": "APK", "kind": "Directory"},
            "applicationId": "dk.frem.app",
            "variantName": "release",
            "elements": [],
        }
        for abi in (None, *package.ABIS):
            filename = f"app-{abi or 'universal'}-release.apk"
            self.write_apk(self.directory / filename, package.ABIS if abi is None else (abi,))
            self.metadata["elements"].append({
                "filters": [] if abi is None else [{"filterType": "ABI", "value": abi}],
                "versionName": "0.4",
                "versionCode": 4,
                "outputFile": filename,
            })
        self.save_metadata()

    def save_metadata(self):
        (self.directory / "output-metadata.json").write_text(json.dumps(self.metadata))

    def write_apk(self, path, abis, *, machine_override=None, payload=b"same-library"):
        with zipfile.ZipFile(path, "w") as apk:
            apk.writestr("classes.dex", b"test-bytecode")
            for abi in abis:
                elf_class, machine = package.ELF_ARCHITECTURES[abi]
                header = bytearray(20)
                header[:4] = b"\x7fELF"
                header[4:7] = bytes((elf_class, 1, 1))
                struct.pack_into("<H", header, 18, machine_override or machine)
                apk.writestr(f"lib/{abi}/libexample.so", header + payload)

    def outputs(self):
        return package.read_outputs(self.directory, "0.4-release")[1]

    def badging(self, version="0.4", code="4", app_id="dk.frem.app"):
        return f"package: name='{app_id}' versionCode='{code}' versionName='{version}'\n"

    def test_reads_all_five_metadata_outputs_and_ignores_stale_apks(self):
        (self.directory / "stale.apk").write_bytes(b"not-an-active-output")
        tag, outputs = package.read_outputs(self.directory)
        self.assertEqual(tag, "0.4-release")
        self.assertEqual(tuple(outputs), (None, *package.ABIS))
        self.assertEqual(outputs["arm64-v8a"].name, "app-arm64-v8a-release.apk")

    def test_rejects_missing_or_duplicate_architecture_and_missing_universal(self):
        cases = [self.metadata["elements"][:-1]]
        for index in (0, 2):
            altered = json.loads(json.dumps(self.metadata["elements"]))
            altered[index] = altered[1]
            cases.append(altered)
        for elements in cases:
            with self.subTest(elements=elements):
                self.metadata["elements"] = elements
                self.save_metadata()
                with self.assertRaises(ValueError):
                    self.outputs()

    def test_rejects_wrong_release_identity_or_filter(self):
        original = json.loads(json.dumps(self.metadata))
        mutations = (
            lambda m: m.update(applicationId="different.app"),
            lambda m: m.update(variantName="debug"),
            lambda m: m["artifactType"].update(type="BUNDLE"),
            lambda m: m["elements"][1].update(versionCode=1004),
            lambda m: m["elements"][1].update(versionName="0.3"),
            lambda m: m["elements"][1]["filters"][0].update(value="mips"),
            lambda m: m["elements"][1]["filters"][0].update(filterType="DENSITY"),
        )
        for mutation in mutations:
            self.metadata = json.loads(json.dumps(original))
            mutation(self.metadata)
            self.save_metadata()
            with self.subTest(metadata=self.metadata), self.assertRaises(ValueError):
                self.outputs()

    def test_rejects_missing_escaping_or_duplicate_file_paths(self):
        for filename in (
            "missing.apk", "../outside.apk", "/tmp/outside.apk",
            self.metadata["elements"][0]["outputFile"],
        ):
            self.metadata["elements"][1]["outputFile"] = filename
            self.save_metadata()
            with self.subTest(filename=filename), self.assertRaises(ValueError):
                self.outputs()

    def test_verifies_native_abis_and_real_elf_architectures(self):
        outputs = self.outputs()
        self.assertEqual(len(package.native_libraries(outputs[None], None)), 4)
        self.assertEqual(len(package.native_libraries(outputs["x86_64"], "x86_64")), 1)
        with self.assertRaisesRegex(ValueError, "Incorrect native ABI set"):
            package.native_libraries(outputs[None], "arm64-v8a")
        self.write_apk(outputs["arm64-v8a"], ("arm64-v8a",), machine_override=62)
        with self.assertRaisesRegex(ValueError, "architecture does not match"):
            package.native_libraries(outputs["arm64-v8a"], "arm64-v8a")

    def test_rejects_invalid_elf_and_unknown_native_directory(self):
        apk = self.outputs()["x86"]
        for name in ("lib/x86/libbad.so", "lib/mips/libbad.so"):
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr(name, b"not-an-elf-library")
            with self.subTest(name=name), self.assertRaises(ValueError):
                package.native_libraries(apk, "x86")

    def test_apk_identity_must_match_and_be_non_debuggable(self):
        package.verify_identity(self.badging(), "0.4-release")
        for badging in (
            self.badging(version="0.3"), self.badging(code="1004"),
            self.badging(app_id="other.app"), self.badging() + "application-debuggable\n", "",
        ):
            with self.subTest(badging=badging), self.assertRaises(ValueError):
                package.verify_identity(badging, "0.4-release")

    def test_certificate_requires_exactly_one_pinned_signer(self):
        certificate = "a" * 64
        valid = f"Signer #1 certificate SHA-256 digest: {certificate}\n"
        package.verify_certificate(valid, certificate)
        for verification in (
            "", valid.replace(certificate, "b" * 64), valid + valid.replace("#1", "#2"),
        ):
            with self.subTest(verification=verification), self.assertRaises(ValueError):
                package.verify_certificate(verification, certificate)

    def test_build_checks_every_apk_and_rejects_different_native_payload(self):
        outputs = self.outputs()
        certificate = "a" * 64

        def command(arguments, **kwargs):
            if arguments[0].endswith("aapt2"):
                return self.badging()
            return f"Signer #1 certificate SHA-256 digest: {certificate}\n"

        with (
            patch.object(package.subprocess, "check_output", side_effect=command) as run,
            contextlib.redirect_stdout(io.StringIO()),
        ):
            package.verify_build(outputs, "0.4-release", Path("tools"), certificate)
            self.assertEqual(run.call_count, 10)
            self.write_apk(outputs["x86"], ("x86",), payload=b"different-library")
            with self.assertRaisesRegex(ValueError, "do not match the universal"):
                package.verify_build(outputs, "0.4-release", Path("tools"), certificate)

    def test_asset_names_keep_universal_compatibility_and_stage_only_five_apks(self):
        outputs = self.outputs()
        output = self.directory / "staged"
        output.mkdir()
        (output / "stale.apk").write_bytes(b"old-build")
        package.stage_assets(outputs, "0.4-release", output)
        self.assertEqual(
            {p.name for p in output.iterdir()},
            {"frem-0.4-release.apk", *(f"frem-0.4-release-{abi}.apk" for abi in package.ABIS)},
        )
        self.assertEqual((output / "frem-0.4-release.apk").read_bytes(), outputs[None].read_bytes())

    def test_download_guide_includes_all_variants_and_measured_sizes(self):
        notes = package.download_notes("0.4-release", self.outputs())
        self.assertIn("Most phones: ARM64", notes)
        self.assertIn("/0.4-release/frem-0.4-release.apk", notes)
        for abi in package.ABIS:
            self.assertIn(f"frem-0.4-release-{abi}.apk", notes)
        self.assertEqual(notes.count("MiB |"), 5)

    def test_staging_cannot_remove_its_own_input_apks(self):
        outputs = self.outputs()
        with self.assertRaisesRegex(ValueError, "must not contain input APKs"):
            package.stage_assets(outputs, "0.4-release", self.directory)
        self.assertTrue(all(apk.exists() for apk in outputs.values()))

    def test_failed_signed_verification_never_stages_assets(self):
        with (
            patch.object(sys, "argv", [
                "package-release.py", "0.4-release", "--apk-directory", str(self.directory),
            ]),
            patch.dict("os.environ", {"ANDROID_HOME": "/sdk"}),
            patch.object(
                package, "verify_build", side_effect=ValueError("Wrong signing certificate"),
            ),
            patch.object(package, "stage_assets") as stage,
            self.assertRaisesRegex(SystemExit, "Wrong signing certificate"),
        ):
            package.main()
        stage.assert_not_called()

    def test_check_build_never_stages_assets_or_checks_release_certificate(self):
        with (
            patch.object(sys, "argv", [
                "package-release.py", "--check-build", "--apk-directory", str(self.directory),
            ]),
            patch.dict("os.environ", {"ANDROID_HOME": "/sdk"}),
            patch.object(package, "verify_build") as verify,
            patch.object(package, "stage_assets") as stage,
            contextlib.redirect_stdout(io.StringIO()),
        ):
            package.main()
            self.assertIsNone(verify.call_args.args[3])
            stage.assert_not_called()


if __name__ == "__main__":
    unittest.main()

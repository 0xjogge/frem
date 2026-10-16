import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "device_runner", Path(__file__).resolve().parents[1] / "run-device-tests.py"
)
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class DeviceRunnerTest(unittest.TestCase):
    def test_requires_completed_nonempty_junit_run(self):
        self.assertTrue(runner.tests_passed("Time: 2.3\n\nOK (18 tests)\n\nINSTRUMENTATION_CODE: -1\n"))
        self.assertTrue(runner.tests_passed("OK (1 test)\n"))
        self.assertFalse(runner.tests_passed("OK (0 tests)\n"))
        self.assertFalse(runner.tests_passed("INSTRUMENTATION_CODE: 0\n"))

    def test_adb_success_does_not_hide_test_failure_or_crash(self):
        for output in (
            "FAILURES!!!\nTests run: 18, Failures: 1\nINSTRUMENTATION_CODE: -1\n",
            "INSTRUMENTATION_FAILED: runner not found\n",
            "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n",
            "OK (18 tests)\nINSTRUMENTATION_ABORTED: runner stopped\n",
        ):
            with self.subTest(output=output):
                self.assertFalse(runner.tests_passed(output))

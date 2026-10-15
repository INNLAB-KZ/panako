import unittest
from monitor_errors import count_java_oom


class OomSignatureTest(unittest.TestCase):
    def test_jvm_options_are_not_heap_failures(self):
        self.assertEqual(0, count_java_oom(
            'NOTE: Picked up JDK_JAVA_OPTIONS: -Xmx3584m -XX:+ExitOnOutOfMemoryError\n'
            '-XX:+HeapDumpOnOutOfMemoryError -XX:OnOutOfMemoryError="command"'))

    def test_uncaught_worker_heap_failure(self):
        self.assertEqual(1, count_java_oom(
            'Exception in thread "panako-redis-worker-stage1-0" '
            'java.lang.OutOfMemoryError: Java heap space'))

    def test_jvm_exit_failure(self):
        self.assertEqual(1, count_java_oom(
            'Terminating due to java.lang.OutOfMemoryError: Java heap space'))

    def test_unqualified_failure_and_startup_option(self):
        self.assertEqual(1, count_java_oom(
            '-XX:+ExitOnOutOfMemoryError\nOutOfMemoryError: Java heap space'))


if __name__ == '__main__':
    unittest.main()

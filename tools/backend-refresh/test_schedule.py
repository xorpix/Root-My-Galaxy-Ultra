#!/usr/bin/env python3
from datetime import date, datetime
import unittest

from schedule import should_run


class ScheduleTests(unittest.TestCase):
    skip = date(2026, 11, 16)

    def test_only_the_selected_utc_date_is_skipped(self):
        for stamp, expected in [
            ('2026-11-15T23:59:59+00:00', True),
            ('2026-11-16T00:00:00+00:00', False),
            ('2026-11-16T03:17:00+00:00', False),
            ('2026-11-16T23:59:59+00:00', False),
            ('2026-11-17T00:00:00+00:00', True),
            ('2026-11-23T03:17:00+00:00', True),
            ('2027-11-16T03:17:00+00:00', True),
            ('2026-11-17T00:30:00+01:00', False),
        ]:
            with self.subTest(stamp=stamp):
                self.assertEqual(expected, should_run('schedule', self.skip, datetime.fromisoformat(stamp)))

    def test_manual_builds_and_pull_requests_still_run(self):
        for event in ('workflow_dispatch', 'pull_request', 'push'):
            self.assertTrue(should_run(event, self.skip, datetime.fromisoformat('2026-11-16T03:17:00+00:00')))

    def test_ambiguous_local_time_is_rejected(self):
        with self.assertRaises(ValueError):
            should_run('schedule', self.skip, datetime(2026, 11, 16))


if __name__ == '__main__':
    unittest.main(verbosity=2)

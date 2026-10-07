#!/usr/bin/env python3
"""Check alert ownership, deduplication and recovery without contacting GitHub."""
import unittest
from unittest.mock import Mock

import notify


class NotifyTests(unittest.TestCase):
    def alert(self, **values):
        return dict({'number': 7, 'user': {'login': 'github-actions[bot]'},
                     'body': 'Maintainer notes\n' + notify.section('owner/repo', '1', 'failure')
                             + '\nMore notes'}, **values)

    def test_failure_opens_one_issue_with_run_link(self):
        api = Mock(side_effect=[[], {'number': 7}])
        notify.update('owner/repo', '123', 'failure', api)
        method, path, body = api.call_args.args
        self.assertEqual(('POST', 'repos/owner/repo/issues'), (method, path))
        self.assertIn('/actions/runs/123', body['body'])

    def test_repeat_failure_updates_issue_and_preserves_maintainer_notes(self):
        api = Mock(side_effect=[[self.alert()], {}])
        notify.update('owner/repo', '124', 'failure', api)
        method, path, body = api.call_args.args
        self.assertEqual(('PATCH', 'repos/owner/repo/issues/7'), (method, path))
        self.assertTrue(body['body'].startswith('Maintainer notes\n'))
        self.assertTrue(body['body'].endswith('\nMore notes'))
        self.assertIn('/actions/runs/124', body['body'])
        self.assertNotIn('state', body)

    def test_success_closes_alert_with_recovery_link(self):
        api = Mock(side_effect=[[self.alert()], {}])
        notify.update('owner/repo', '125', 'success', api)
        body = api.call_args.args[2]
        self.assertEqual('closed', body['state'])
        self.assertIn('/actions/runs/125', body['body'])

    def test_success_without_alert_creates_nothing(self):
        api = Mock(return_value=[])
        notify.update('owner/repo', '126', 'success', api)
        self.assertEqual(1, api.call_count)

    def test_user_issues_and_pull_requests_are_never_modified(self):
        user_issue = self.alert(user={'login': 'maintainer'})
        pr = self.alert(pull_request={'url': 'unused'})
        api = Mock(side_effect=[[user_issue, pr], {'number': 8}])
        notify.update('owner/repo', '127', 'failure', api)
        self.assertEqual('POST', api.call_args.args[0])

    def test_alert_on_later_page_is_found_without_duplicate(self):
        api = Mock(side_effect=[[{'number': n} for n in range(100)], [self.alert()], {}])
        notify.update('owner/repo', '128', 'failure', api)
        self.assertIn('page=2', api.call_args_list[1].args[1])
        self.assertEqual('PATCH', api.call_args.args[0])


if __name__ == '__main__':
    unittest.main(verbosity=2)

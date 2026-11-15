#!/usr/bin/env python3
"""Skip one dated scheduled release while preserving manual and future runs."""
import argparse
from datetime import date, datetime, timezone
import os


def should_run(event, skip_date, now):
    if now.tzinfo is None:
        raise ValueError('An aware timestamp is required')
    return event != 'schedule' or now.astimezone(timezone.utc).date() != skip_date


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--event', required=True)
    parser.add_argument('--skip-date', required=True, type=date.fromisoformat)
    args = parser.parse_args()
    run = should_run(args.event, args.skip_date, datetime.now(timezone.utc))
    message = ('Scheduled release proceeds normally.' if run else
               f'Skipping the {args.skip_date} scheduled release after the manual refresh; future Mondays remain enabled.')
    print(message)
    if output := os.environ.get('GITHUB_OUTPUT'):
        with open(output, 'a') as stream:
            stream.write(f'run={str(run).lower()}\n')
    if summary := os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(summary, 'a') as stream:
            stream.write(message + '\n')


if __name__ == '__main__':
    main()

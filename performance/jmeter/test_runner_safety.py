import argparse
import contextlib
import csv
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from run import Failure, bounded, delivery_lag, main, measured_csv


class RunnerSafetyTest(unittest.TestCase):
    def test_concurrency_is_bounded(self):
        self.assertEqual(8,bounded(1,8)('8'))
        for value in ('0','9'):
            with self.assertRaises(argparse.ArgumentTypeError): bounded(1,8)(value)

    def test_external_target_option_is_rejected_before_any_environment_is_created(self):
        with patch('sys.argv',['run.py','--api-url','https://external.example']), patch('run.PerformanceEnvironment') as env:
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                main()
            self.assertEqual(2,error.exception.code)
            env.assert_not_called()

    def test_warmup_must_include_the_ramp(self):
        with patch('sys.argv',['run.py','--warmup','3','--ramp','5']), patch('run.PerformanceEnvironment') as env:
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit): main()
            env.assert_not_called()

    def test_creation_warmup_rows_are_excluded_from_the_measured_file(self):
        with tempfile.TemporaryDirectory() as folder:
            source=Path(folder)/'raw.jtl';target=Path(folder)/'measured.jtl'
            source.write_text('timeStamp,phase\n1000,warmup\n2000,measurement\n3000,measurement\n')
            measured_csv(source,target,5,create=True)
            with target.open() as stream: self.assertEqual(2,len(list(csv.DictReader(stream))))

    def test_delivery_lag_is_matched_by_event_id(self):
        env=Mock()
        env.sql.side_effect=[json.dumps([{'id':'a','at':10},{'id':'b','at':11}]),json.dumps([{'id':'b','at':11.5},{'id':'a','at':10.2}])]
        lag=delivery_lag(env,2)
        self.assertAlmostEqual(200,lag['p50Ms'])
        self.assertAlmostEqual(500,lag['p95Ms'])
        self.assertEqual(2,lag['samples'])

    def test_negative_delivery_lag_is_rejected(self):
        env=Mock()
        env.sql.side_effect=[json.dumps([{'id':'a','at':10}]),json.dumps([{'id':'a','at':9}])]
        with self.assertRaises(Failure): delivery_lag(env,1)


if __name__=='__main__': unittest.main()

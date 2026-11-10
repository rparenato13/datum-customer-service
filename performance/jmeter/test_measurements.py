import csv
import io
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as E

from data import customers, cpf, status_requests
from measurements import analyse, prometheus, quantile, total

ROOT = Path(__file__).resolve().parent


class MeasurementTest(unittest.TestCase):
    def result(self, code='200', success='true', application='NONE'):
        folder=tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        path=Path(folder.name)/'samples.jtl'
        with path.open('w',newline='') as stream:
            writer=csv.DictWriter(stream,fieldnames=['timeStamp','elapsed','responseCode','success','applicationCode'])
            writer.writeheader()
            for index,value in enumerate([10,20,30,40,100]):
                writer.writerow(dict(timeStamp=100000+index*100,elapsed=value,responseCode=code,success=success,applicationCode=application))
        return path

    def test_percentiles_and_window_use_measured_samples(self):
        data=analyse(self.result(),200)
        self.assertEqual((30,100,100),(data['p50Ms'],data['p95Ms'],data['p99Ms']))
        self.assertEqual(10,data['throughput'])
        self.assertEqual(0,data['errors'])

    def test_timeout_stays_an_http_error(self):
        data=analyse(self.result('504','false','SCORE_TIMEOUT'),504)
        self.assertEqual(1,data['errorRate'])
        self.assertEqual(5,data['errors'])

    def test_expected_timeout_cannot_be_converted_to_success(self):
        with self.assertRaises(ValueError):
            analyse(self.result('504','true','SCORE_TIMEOUT'),504)

    def test_wrong_application_code_is_not_accepted_as_timeout(self):
        with self.assertRaises(ValueError):
            analyse(self.result('504','false','OTHER'),504)

    def test_unexpected_authentication_error_fails_the_experiment(self):
        with self.assertRaises(ValueError):
            analyse(self.result('401','false','AUTHENTICATION_REQUIRED'),200)

    def test_no_samples_cannot_produce_a_report(self):
        path=self.result()
        path.write_text('timeStamp,elapsed,responseCode,success,applicationCode\n')
        with self.assertRaises(ValueError):
            analyse(path,200)

    def test_prometheus_keeps_tags_separate_and_drops_nan(self):
        rows=prometheus('x{outcome="success"} 2\nx{outcome="error"} 1\ny NaN\n# TYPE x counter\n')
        self.assertEqual(2,total(rows,'x',outcome='success'))
        self.assertEqual(3,total(rows,'x'))
        self.assertEqual(2,len(rows))

    def test_generated_dataset_has_unique_valid_check_digits(self):
        rows=list(customers(500))
        self.assertEqual(500,len({row['cpf'] for row in rows}))
        self.assertTrue(all(len(row['cpf'])==11 and row['email'].endswith('@example.test') for row in rows))
        self.assertEqual('52998224725',cpf(529982247))

    def test_status_producer_matches_approved_schema_and_utc_format(self):
        import json
        from jsonschema import Draft202012Validator, FormatChecker
        schema=json.loads((ROOT.parents[1]/'contracts/events/schemas/customer-status-change.v1.schema.json').read_text())
        validator=Draft202012Validator(schema,format_checker=FormatChecker())
        events=status_requests([1,2,3])
        for event in events:
            validator.validate(event)
            self.assertRegex(event['occurredAt'],r'\.[0-9]{6}Z$')
        self.assertEqual(3,len({event['eventId'] for event in events}))

    def test_create_dataset_stops_at_eof_and_never_recycles(self):
        tree=E.parse(ROOT/'D-create.jmx')
        dataset=tree.getroot().find('.//CSVDataSet')
        props={node.get('name'):node.text for node in dataset}
        self.assertEqual('false',props['recycle'])
        self.assertEqual('true',props['stopThread'])

    def test_plans_keep_auth_in_memory_and_do_not_mask_http_errors(self):
        for path in ROOT.glob('*.jmx'):
            tree=E.parse(path)
            props={node.get('name'):node.text for node in tree.iter() if node.get('name')}
            self.assertEqual('false',props['Assertion.assume_success'])
            text=path.read_text()
            self.assertIn("System.getenv('DATUM_TOKEN')",text)
            self.assertNotIn('access_token=',text)
            self.assertEqual('customer-service',props['HTTPSampler.domain'])


if __name__=='__main__':
    unittest.main()

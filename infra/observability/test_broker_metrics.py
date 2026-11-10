import json
import unittest
from broker_metrics import ROOT, render


class BrokerMetricsTest(unittest.TestCase):
    def queues(self):
        contract = json.loads((ROOT / 'contracts/events/topology.v1.json').read_text())
        names = []
        for consumer in contract['consumers']:
            names += [consumer['queue'], consumer['deadQueue']] + [r['queue'] for r in consumer['retries']]
        return [dict(name=name, vhost='datum', messages_ready=2, messages_unacknowledged=1, messages=3) for name in names]

    def test_only_fixed_labels_and_approved_queues(self):
        rows = self.queues()
        rows.append(dict(name='private@example.test', vhost='datum', messages=999))
        text = render(rows)
        self.assertNotIn('private', text)
        self.assertEqual(30, sum(line.startswith('datum_broker_messages_') for line in text.splitlines()))
        self.assertIn('kind="dlq"', text)
        self.assertIn('datum_broker_collection_up 1', text)

    def test_missing_queue_must_not_look_empty(self):
        with self.assertRaises(KeyError):
            render(self.queues()[1:])

    def test_missing_statistics_must_not_look_empty(self):
        rows = self.queues()
        del rows[0]['messages_ready']
        with self.assertRaises(KeyError):
            render(rows)

    def test_invalid_statistics_are_rejected(self):
        rows = self.queues()
        rows[0]['messages_ready'] = 'private'
        with self.assertRaises(ValueError):
            render(rows)


if __name__ == '__main__':
    unittest.main()

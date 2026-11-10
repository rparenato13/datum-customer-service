"""Fixtures sinteticas deterministicas; nunca recebe dados reais como entrada."""
import csv
from pathlib import Path


def cpf(number):
    digits = [int(value) for value in str(number).zfill(9)]
    if len(digits) != 9:
        raise ValueError('Indice sintetico fora do intervalo.')
    for width in (10, 11):
        value = (sum(n * w for n, w in zip(digits, range(width, 1, -1))) * 10) % 11
        digits.append(0 if value == 10 else value)
    return ''.join(map(str, digits))


def customers(count, offset=100000000):
    for index in range(count):
        yield dict(name='Cliente Sintetico ' + str(offset + index), cpf=cpf(offset + index),
            email='cliente.' + str(offset + index) + '@example.test', status='ACTIVE')


def write_csv(path, rows):
    rows = list(rows)
    if not rows:
        raise ValueError('Dataset vazio.')
    with path.open('w', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def status_requests(ids):
    from datetime import datetime, timezone
    import uuid
    return [dict(eventId=str(uuid.uuid4()), eventType='CUSTOMER_STATUS_CHANGE', schemaVersion=1,
        occurredAt=datetime.now(timezone.utc).isoformat(timespec='microseconds').replace('+00:00','Z'),
        customerId=identifier, status='INACTIVE', expectedVersion=0) for identifier in ids]

"""Distribuicao oficial fixa e checksum SHA-512 verificado antes da extracao."""
import hashlib
from pathlib import Path
import tarfile
import urllib.request

VERSION = "5.6.3"
CHECKSUM = "5978a1a35edb5a7d428e270564ff49d2b1b257a65e17a759d259a9283fc17093e522fe46f474a043864aea6910683486340706d745fcdf3db1505fd71e689083"
archive = Path('/tmp/jmeter.tgz')
with urllib.request.urlopen('https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-' + VERSION + '.tgz', timeout=120) as response:
    archive.write_bytes(response.read())
if hashlib.sha512(archive.read_bytes()).hexdigest() != CHECKSUM:
    raise ValueError('Checksum da distribuicao JMeter divergente.')
with tarfile.open(archive) as distribution:
    distribution.extractall('/opt', filter='data')
archive.unlink()

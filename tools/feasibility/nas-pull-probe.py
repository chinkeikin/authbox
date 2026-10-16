import hashlib
import json
import pathlib
import ssl
import tempfile
import urllib.parse
import urllib.request

EXPECTED = 'EXPECTED_SHA256_PLACEHOLDER'
URL = 'https://raw.githubusercontent.com/chinkeikin/authbox/1f519d725c4d59a574eb7edb4e6d07069321cc64/LICENSE'


class IntegrityError(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, new_url):
        raise ValueError('Redirects are not allowed in this probe')


def pull(url, expected_hash, destination):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != 'https' or parsed.username or parsed.password:
        raise ValueError('HTTPS URL without embedded credentials required')
    context = ssl.create_default_context()
    opener = urllib.request.build_opener(NoRedirect(), urllib.request.HTTPSHandler(context=context))
    temporary_path = None
    try:
        with opener.open(url, timeout=20) as response:
            if response.status != 200:
                raise ValueError('Unexpected response status')
            with tempfile.NamedTemporaryFile(dir=destination.parent, prefix='.partial-', delete=False) as output:
                temporary_path = pathlib.Path(output.name)
                digest = hashlib.sha256()
                size = 0
                while chunk := response.read(16384):
                    size += len(chunk)
                    if size > 131072:
                        raise ValueError('Probe response exceeded size limit')
                    output.write(chunk)
                    digest.update(chunk)
        if digest.hexdigest() != expected_hash:
            raise IntegrityError('Checksum mismatch')
        temporary_path.replace(destination)
        return size
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)


with tempfile.TemporaryDirectory(prefix='authbox-feasibility-') as directory:
    destination = pathlib.Path(directory) / 'snapshot.bin'
    size = pull(URL, EXPECTED, destination)
    baseline = destination.read_bytes()
    assert hashlib.sha256(baseline).hexdigest() == EXPECTED
    try:
        pull(URL, '0' * 64, destination)
    except IntegrityError:
        pass
    else:
        raise AssertionError('Invalid checksum accepted')
    assert destination.read_bytes() == baseline
    try:
        pull(URL.replace('https://', 'http://', 1), EXPECTED, destination)
    except ValueError:
        pass
    else:
        raise AssertionError('Plain HTTP accepted')
    assert list(pathlib.Path(directory).glob('.partial-*')) == []

assert not pathlib.Path(directory).exists()
print(json.dumps({
    'httpsDownloadPassed': True,
    'bytes': size,
    'sha256MatchesLocalFixture': True,
    'checksumFailurePreservesPreviousFile': True,
    'httpRejected': True,
    'partialFilesCleaned': True,
    'temporaryDirectoryCleaned': True,
    'tls': ssl.OPENSSL_VERSION,
    'scope': 'public pinned HTTPS fixture; not authenticated Cloudflare backup or encrypted restore',
}, indent=2))

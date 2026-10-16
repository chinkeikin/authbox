set -eu
uname -s
uname -m
if command -v python3 >/dev/null 2>&1; then
  python3 --version
  python3 -c 'import ssl, urllib.request; print("python_https=available"); print(ssl.OPENSSL_VERSION)'
else
  printf '%s\n' 'python_https=unavailable'
fi
if command -v docker >/dev/null 2>&1; then
  docker --version
else
  printf '%s\n' 'docker=unavailable'
fi
if command -v curl >/dev/null 2>&1; then
  curl --version | head -n 1
fi

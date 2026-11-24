# Security Policy

## Supported Versions

| Version | Supported |
| --- | --- |
| 1.0.0 | ✅ |

## Reporting a Vulnerability

Please report vulnerabilities privately through GitHub's **Private vulnerability reporting** ("Report a vulnerability" in this repository's Security tab) instead of opening a public issue.

Hinata is a solo-developer project; reports are read and handled on a best-effort basis.

脆弱性の報告は、公開Issueではなく、このリポジトリの Security タブにある **Private vulnerability reporting** からお願いします。個人開発のため、対応はベストエフォートとなります。

## Verifying Release APKs / リリースAPKの検証

Every Release attaches `SHA256SUMS.txt` with the SHA-256 checksum of each APK.

Signing certificate SHA-256 fingerprint (the same key signs every release; verify with AppVerifier or `apksigner verify --print-certs`):

```
0A:CB:E0:F2:5B:2E:8C:1E:F0:82:91:9C:87:30:65:3B:06:C7:6B:D1:85:98:BB:61:A2:A4:01:28:4E:F7:F6:D3
```

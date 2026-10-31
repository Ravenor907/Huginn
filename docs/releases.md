# Releases and how to check them

Every release has three downloads: `Huginn-Windows.zip`, `Huginn.flatpak` and `Muninn.apk`. Next to each one
the release has:

- its SHA-256, shown by GitHub beside the file on the release page.
- `<file>.sig`: the project's signature of the file.
- a GitHub build attestation: GitHub's own record that the file was built by this repository's CI, from a
  given commit.

## The project release key

The project signs every release file with its own Ed25519 key. No outside company or account is involved.

- The public half is in [release-key.pem](release-key.pem) and built into both apps:
  `705d7c9eb0626439b6ab749bbeefc9271876249c701914d60902a6b2b7341d06` (32 bytes, hex).
- The private half is only in the repository's GitHub secrets, as `RELEASE_SIGNING_KEY` (PEM, PKCS#8). It has
  never been committed. The release job signs with it and checks the signature against `release-key.pem`
  before publishing. If the secret is missing or wrong, the release fails and nothing is published.
- `<file>.sig` is 64 bytes: the Ed25519 signature (RFC 8032) of the file's raw bytes, made with
  `openssl pkeyutl -sign -rawin`.

Huginn (Windows and Linux) and Muninn install an update only when both are true:

1. its SHA-256 matches the one GitHub lists for the file, and
2. its `.sig` is a good signature from the project key.

Otherwise they say so and install nothing.

If the key is ever lost, the apps can't accept updates signed with a new one. Everyone would have to download
the new version by hand once. Keep a copy of the private key somewhere safe and offline.

## Check a download by hand

You need OpenSSL 3 (most Linux systems have it; on Windows it comes with Git for Windows). In the folder with the
download, its `.sig` and [release-key.pem](release-key.pem):

```
openssl pkeyutl -verify -pubin -inkey release-key.pem -rawin -in Huginn-Windows.zip -sigfile Huginn-Windows.zip.sig
```

It prints `Signature Verified Successfully` for a good file. For the SHA-256, compare this with the digest GitHub
shows beside the file:

```
sha256sum Huginn-Windows.zip
```

## Check the build attestation

With the GitHub CLI (`gh`):

```
gh attestation verify Huginn-Windows.zip --repo Ravenor907/Huginn-Muninn
```

It shows the workflow and commit that built the file. The same works for `Huginn.flatpak` and `Muninn.apk`.

## Windows warnings

Huginn.exe isn't signed with a paid Windows code-signing certificate, so Windows SmartScreen may say it is
from an unknown publisher the first time. Choose **More info**, then **Run anyway**. The signature and
attestation above are how to check that the file really came from this project. Huginn.exe is built with
Control Flow Guard, CET shadow stacks, ASLR and DEP, and CI fails the build if any of them is missing.

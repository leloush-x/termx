# Distro manifest signing

The checked-in `app/src/main/assets/distro-manifest.json` is signed with OpenSSH's
Ed25519 signature envelope. The app verifies its `termx-distro-manifest`
namespace and the embedded public key before parsing any JSON.

The private key is kept outside Git at:

`/root/.config/termx-manifest/manifest_ed25519`

Keep an owner-controlled backup of this key. It is not a GitHub Actions secret;
the release APK embeds only the public key. To update the manifest, edit the
JSON, sign the exact bytes, replace the signature asset, and verify it locally:

```sh
ssh-keygen -Y sign \
  -f /root/.config/termx-manifest/manifest_ed25519 \
  -n termx-distro-manifest \
  app/src/main/assets/distro-manifest.json
mv app/src/main/assets/distro-manifest.json.sig app/src/main/assets/distro-manifest.sig
ssh-keygen -Y verify \
  -f /tmp/termx-allowed-signers \
  -I termx-distro-manifest \
  -n termx-distro-manifest \
  -s app/src/main/assets/distro-manifest.sig \
  < app/src/main/assets/distro-manifest.json
```

Create that temporary allowed-signers file from the public key with:

```sh
awk '{ print "termx-distro-manifest " $1 " " $2 }' \
  /root/.config/termx-manifest/manifest_ed25519.pub > /tmp/termx-allowed-signers
```

Update package URLs, sizes, and SHA-256 values from the upstream release before
signing. The manifest is bundled, so it causes no network request at startup.
Rootfs downloads occur only after the user taps Install.

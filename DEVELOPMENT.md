# Hermex Android — Development Notes

## Certificate Pinning

**Removed.** The app previously used OkHttp `CertificatePinner` to trust a self-signed
cert on the Hermes Dashboard (old port 8443). The dashboard now serves on port 9119
plain HTTP (Tailscale-internal), so no TLS pinning is needed. The trust-all SSL code
and `usesCleartextTraffic` app-wide flag were also removed — cleartext is now scoped
to `100.80.204.66` only via `network_security_config.xml`.

### Legacy (for reference)

If TLS is ever re-enabled:
```
PIN=$(openssl s_client -connect 100.80.204.66:9119 </dev/null 2>/dev/null | \
  openssl x509 -pubkey -noout | \
  openssl pkey -pubin -outform der | \
  openssl dgst -sha256 -binary | base64)
echo "sha256/$PIN"
```

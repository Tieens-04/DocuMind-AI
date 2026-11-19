# Enterprise Information Security and Access Control Standard

Document Classification: PUBLIC — Applies to all full-time employees, contractors, and engineering teams across every department.
Effective Date: 2026-01-15 | Owner: Information Security Governance Committee

## 1. Multi-Factor Authentication and Passphrase Baseline

Every employee accessing corporate identity providers, cloud consoles, source code repositories, or internal knowledge assistants must authenticate using phishing-resistant Multi-Factor Authentication (MFA). Hardware FIDO2 and WebAuthn security keys are mandatory for all engineering, finance, and human resources personnel. Legacy SMS one-time passcodes (OTP) and unencrypted email verification codes are strictly prohibited for privileged administrative accounts and VPN concentrators.

Corporate single sign-on accounts require a unique passphrase with a minimum length of 14 characters combining upper-case, lower-case, numeric, and symbol entropy. Password reuse across external services or shared departmental mailboxes is forbidden. Workstations must enforce full-disk encryption with FileVault or BitLocker before receiving corporate identity certificates.

```yaml
# Example identity policy snippet — headings inside code fences must remain unparsed
# # Not a section heading
security:
  mfa_mode: fido2_webauthn
  sms_otp_admin_allowed: false
  min_passphrase_length: 14
  max_session_idle_minutes: 30
```

Account lockouts trigger automatically after 5 consecutive failed sign-in attempts within 10 minutes. Helpdesk personnel must verify government-issued photo identification over live video before issuing temporary MFA recovery tokens.

## 2. Service Credential Rotation and Severity-1 Incident Escalation

All production API keys, database service credentials, OAuth client secrets, and internal mTLS client certificates must be rotated every 90 days through the centralized secrets vault. Static plaintext secrets inside application repositories, container images, or environment backups are forbidden and automatically blocked by pre-commit and CI scanners. Emergency credential revocation must complete within 30 minutes whenever a laptop is reported lost or an unauthorized token exposure is suspected.

When monitoring alerts or staff observations indicate a Severity-1 (P1) security incident—such as unauthorized access to confidential customer or HR records, active ransomware indicators, or credential exfiltration—personnel must escalate to the Security Operations Center (SOC) within 15 minutes of detection via the dedicated hotline and incident channel. All authentication, document access, and AI query audit logs must be retained immutably for at least 365 days to support forensic investigation and compliance audits.

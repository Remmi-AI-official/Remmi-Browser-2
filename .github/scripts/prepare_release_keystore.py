#!/usr/bin/env python3
"""
Prepares, validates, and auto-discovers release keystore credentials for GitHub Actions CI/CD.
Handles:
  - Base64 decoding with URL-safe variants, trailing padding fixes, whitespace/quotes/prefix stripping
  - Store password verification with clear diagnostics (no silent /dev/null failures)
  - Auto-detection of swapped STORE_PASSWORD and KEY_PASSWORD
  - Dynamic discovery of keystore aliases (case-insensitive matching & single-alias auto-selection)
  - Defaulting KEY_PASSWORD to STORE_PASSWORD (standard PKCS12 behavior)
  - Exporting verified credentials to GITHUB_ENV
"""

import os
import sys
import base64
import re
import subprocess

def run_keytool(args):
    cmd = ['keytool'] + args
    return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

def main():
    keystore_b64 = os.environ.get('KEYSTORE_BASE64', '').strip()
    store_pass = os.environ.get('STORE_PASSWORD', '').strip()
    key_alias = os.environ.get('KEY_ALIAS', '').strip()
    key_pass = os.environ.get('KEY_PASSWORD', '').strip()
    keystore_file = os.environ.get('KEYSTORE_FILE', '').strip()

    if not keystore_file:
        keystore_file = os.path.join(os.getcwd(), 'release-keystore.jks')

    if not keystore_b64:
        print("::error::CRITICAL RELEASE FAILURE: REMMI_RELEASE_KEYSTORE_B64 secret is not configured or empty.")
        sys.exit(1)

    if not store_pass and not key_pass:
        print("::error::CRITICAL RELEASE FAILURE: Neither REMMI_RELEASE_STORE_PASSWORD nor REMMI_RELEASE_KEY_PASSWORD is provided.")
        sys.exit(1)

    if not store_pass:
        store_pass = key_pass

    # Sanitize base64 string
    # 1. Strip surrounding single or double quotes
    if (keystore_b64.startswith('"') and keystore_b64.endswith('"')) or \
       (keystore_b64.startswith("'") and keystore_b64.endswith("'")):
        keystore_b64 = keystore_b64[1:-1].strip()

    # 2. Strip data URI prefix if user pasted a data URL
    if 'base64,' in keystore_b64:
        keystore_b64 = keystore_b64.split('base64,')[1].strip()

    # 3. Convert URL-safe base64 (- and _) to standard base64 (+ and /)
    keystore_b64 = keystore_b64.replace('-', '+').replace('_', '/')

    # 4. Remove all whitespace and invalid characters
    cleaned_b64 = re.sub(r'[^A-Za-z0-9+/=]', '', keystore_b64)

    # 5. Fix padding if necessary
    remainder = len(cleaned_b64) % 4
    if remainder != 0:
        cleaned_b64 += '=' * (4 - remainder)

    try:
        decoded_bytes = base64.b64decode(cleaned_b64)
    except Exception as e:
        print(f"::error::CRITICAL RELEASE FAILURE: Failed to decode base64 keystore: {e}")
        sys.exit(1)

    if len(decoded_bytes) < 100:
        print(f"::error::CRITICAL RELEASE FAILURE: Decoded keystore is only {len(decoded_bytes)} bytes. Keystore secret appears corrupt or empty.")
        sys.exit(1)

    if os.path.exists(keystore_file):
        try:
            os.remove(keystore_file)
        except Exception:
            pass

    with open(keystore_file, 'wb') as f:
        f.write(decoded_bytes)

    # Step 1: Verify store password
    res = run_keytool(['-list', '-keystore', keystore_file, '-storepass', store_pass])
    
    # Check if store_pass and key_pass might have been swapped
    if res.returncode != 0 and key_pass and key_pass != store_pass:
        res_swapped = run_keytool(['-list', '-keystore', keystore_file, '-storepass', key_pass])
        if res_swapped.returncode == 0:
            print("::notice::STORE_PASSWORD and KEY_PASSWORD were swapped in repository secrets; automatically corrected.")
            store_pass, key_pass = key_pass, store_pass
            res = res_swapped

    if res.returncode != 0:
        err_msg = (res.stderr or res.stdout).strip()
        print("::error::CRITICAL RELEASE FAILURE: Failed to unlock release keystore with STORE_PASSWORD.")
        print(f"::error::Keytool error details: {err_msg}")
        sys.exit(1)

    # Step 2: Parse available aliases from keystore
    found_aliases = []
    for line in res.stdout.splitlines():
        if 'PrivateKeyEntry' in line or 'trustedCertEntry' in line:
            parts = line.split(',')
            if parts:
                found_aliases.append(parts[0].strip())

    if not found_aliases:
        # Fallback to verbose listing format if needed
        res_v = run_keytool(['-list', '-v', '-keystore', keystore_file, '-storepass', store_pass])
        for line in res_v.stdout.splitlines():
            stripped = line.strip()
            if stripped.startswith('Alias name:'):
                found_aliases.append(stripped.split(':', 1)[1].strip())

    print(f"Keystore unlocked successfully. Discovered alias(es) in keystore: {found_aliases}")

    resolved_alias = None
    if key_alias and key_alias in found_aliases:
        resolved_alias = key_alias
    elif key_alias:
        # Try case-insensitive matching
        for fa in found_aliases:
            if fa.lower() == key_alias.lower():
                print(f"::notice::Matched alias '{fa}' case-insensitively with configured alias '{key_alias}'.")
                resolved_alias = fa
                break

    if not resolved_alias:
        if len(found_aliases) == 1:
            resolved_alias = found_aliases[0]
            if key_alias:
                print(f"::warning::Configured alias '{key_alias}' was not found in keystore, but keystore contains exactly one valid alias '{resolved_alias}'. Automatically adopting '{resolved_alias}'.")
            else:
                print(f"::notice::Auto-selected sole keystore alias '{resolved_alias}'.")
        elif not found_aliases:
            print("::error::CRITICAL RELEASE FAILURE: No private key or certificate aliases found in the release keystore.")
            sys.exit(1)
        else:
            print(f"::error::CRITICAL RELEASE FAILURE: Alias '{key_alias}' not found in keystore.")
            print(f"::error::Available aliases are: {', '.join(found_aliases)}. Please set REMMI_RELEASE_KEY_ALIAS in GitHub Secrets to one of these.")
            sys.exit(1)

    # Step 3: Ensure key password is set (PKCS12 standard uses store password)
    if not key_pass:
        key_pass = store_pass

    # Step 4: Validate chosen alias with keytool
    res_alias = run_keytool(['-list', '-keystore', keystore_file, '-storepass', store_pass, '-alias', resolved_alias])
    if res_alias.returncode != 0:
        err_msg = (res_alias.stderr or res_alias.stdout).strip()
        print(f"::error::CRITICAL RELEASE FAILURE: Failed to access alias '{resolved_alias}': {err_msg}")
        sys.exit(1)

    print(f"Release keystore and alias '{resolved_alias}' validated successfully.")

    # Step 5: Export to GITHUB_ENV
    github_env = os.environ.get('GITHUB_ENV')
    if github_env and os.path.exists(github_env):
        with open(github_env, 'a') as f:
            f.write(f"KEYSTORE_PATH={keystore_file}\n")
            f.write(f"STORE_PASSWORD={store_pass}\n")
            f.write(f"KEY_ALIAS={resolved_alias}\n")
            f.write(f"KEY_PASSWORD={key_pass}\n")
    else:
        print(f"KEYSTORE_PATH={keystore_file}")
        print(f"STORE_PASSWORD={store_pass}")
        print(f"KEY_ALIAS={resolved_alias}")
        print(f"KEY_PASSWORD={key_pass}")

if __name__ == '__main__':
    main()

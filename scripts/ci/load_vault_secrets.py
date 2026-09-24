"""Loads the secrets a pipeline consumer needs from Azure Key Vault into that job's environment.

Both callers log in to Azure first (AzureCLI@2 with a workload-identity service connection on Azure DevOps,
azure/login with OIDC in the GitHub reset workflow), so `az` is already authenticated. The list of secrets per
consumer and the "vault secret name -> environment variable" mapping live in .ci/pipeline-contract.json.

    python3 scripts/ci/load_vault_secrets.py --provider azure  --consumer azure-pr-ci --vault <name>
    python3 scripts/ci/load_vault_secrets.py --provider github --consumer ci-provider-reset --vault <name>

Only the secrets the consumer lists are read. Each value is registered with the log masker BEFORE it is exported:
  azure : ##vso[task.setvariable variable=NAME;isSecret=true]
  github: ::add-mask:: and $GITHUB_ENV
A missing, disabled or empty secret fails the step naming the secret. A multi-line or control-character value is
refused before anything is emitted. No message ever contains a secret value.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

DEFAULT_CONTRACT = Path(__file__).resolve().parents[2] / ".ci" / "pipeline-contract.json"
SECRET_NAME = re.compile(r"^[A-Za-z0-9-]{1,127}$")
ENV_NAME = re.compile(r"^[A-Z][A-Z0-9_]{0,63}$")
CONTROL_CHARS = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")


class VaultError(Exception):
    pass


def run_az(command: list[str]) -> tuple[int, str]:
    """Runs az and returns (exit code, stdout). stderr is discarded: it is never surfaced."""
    try:
        result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8")
    except FileNotFoundError:
        return 127, ""
    return result.returncode, result.stdout


def read_secret(vault: str, name: str, runner=run_az) -> str:
    command = ["az", "keyvault", "secret", "show", "--vault-name", vault, "--name", name,
               "--query", "{value:value,enabled:attributes.enabled}", "-o", "json"]
    code, stdout = runner(command)
    if code != 0:
        raise VaultError(f"Key Vault secret '{name}' could not be read (az exit {code}); it may be missing or the "
                         "identity lacks the Key Vault Secrets User role on it.")
    try:
        payload = json.loads(stdout)
    except ValueError:
        raise VaultError(f"Key Vault secret '{name}' returned an unreadable response.") from None
    if payload.get("enabled") is False:
        raise VaultError(f"Key Vault secret '{name}' is disabled.")
    value = payload.get("value")
    if value is None or value == "":
        raise VaultError(f"Key Vault secret '{name}' has an empty value.")
    return value


def select_secrets(contract: dict, consumer: str) -> list[str]:
    consumers = contract.get("secretConsumers", {})
    if consumer not in consumers:
        raise VaultError(f"Unknown consumer '{consumer}'.")
    names = consumers[consumer]["secrets"]
    unknown = [n for n in names if n not in contract["keyVault"]["secrets"]]
    if unknown:
        raise VaultError(f"Not in the contract's Key Vault catalogue: {', '.join(unknown)}")
    return list(names)


def azure_escape(value: str) -> str:
    return value.replace("%", "%AZP25").replace("\r", "%0D").replace("\n", "%0A")


def github_escape(value: str) -> str:
    return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def prepare(name: str, env_name: str, value: str) -> tuple[str, str]:
    if not ENV_NAME.match(env_name):
        raise VaultError(f"Key Vault secret '{name}' maps to an invalid environment variable name.")
    if "\n" in value or "\r" in value:
        raise VaultError(f"Key Vault secret '{name}' contains a line break; store multi-line values as single-line base64. "
                         "Nothing was exported for it.")
    if CONTROL_CHARS.search(value):
        raise VaultError(f"Key Vault secret '{name}' contains control characters; nothing was exported for it.")
    return env_name, value


def main(argv: list[str] | None = None, runner=run_az, out=None) -> int:
    out = out or sys.stdout
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--provider", required=True, choices=["github", "azure"])
    parser.add_argument("--consumer", required=True)
    parser.add_argument("--vault", default=os.environ.get("AZURE_KEY_VAULT_NAME", ""))
    parser.add_argument("--contract", default=str(DEFAULT_CONTRACT))
    args = parser.parse_args(argv)

    with open(args.contract, encoding="utf-8") as handle:
        contract = json.load(handle)
    env_file = None
    try:
        names = select_secrets(contract, args.consumer)
        if not args.vault:
            raise VaultError("The Key Vault name is required (AZURE_KEY_VAULT_NAME or --vault).")
        if args.provider == "github" and not os.environ.get("GITHUB_ENV"):
            raise VaultError("GITHUB_ENV is not set.")
        # Phase 1: read and validate EVERY secret before anything is emitted, so nothing partial is left behind.
        prepared: list[tuple[str, str]] = []
        for name in names:
            if not SECRET_NAME.match(name):
                raise VaultError("A secret name in the contract is not a valid Key Vault secret name.")
            entry = contract["keyVault"]["secrets"][name]
            prepared.append(prepare(name, entry["env"], read_secret(args.vault, name, runner)))
        # Phase 2: emit.
        if args.provider == "github":
            env_file = open(os.environ["GITHUB_ENV"], "a", encoding="utf-8", newline="\n")
        for env_name, value in prepared:
            if args.provider == "github":
                out.write(f"::add-mask::{github_escape(value)}\n")
                env_file.write(f"{env_name}={value}\n")
            else:
                out.write(f"##vso[task.setvariable variable={env_name};isSecret=true]{azure_escape(value)}\n")
    except VaultError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    except Exception as exc:  # never let an unexpected error carry a value into a log
        print(f"Unexpected error while loading secrets ({type(exc).__name__}); no value is shown.", file=sys.stderr)
        return 1
    finally:
        if env_file:
            env_file.close()
    out.write(f"Loaded {len(names)} secret(s) from Key Vault: {', '.join(names)}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

"""Loads non-secret configuration from GitHub repository variables for an Azure DevOps pipeline.

GitHub repository variables are the single source of truth for non-secret configuration; Azure keeps no duplicate
variable group. This script reads them through the GitHub API with the pipeline's own GitHub token (GH_PAT) and
exports the ones a pipeline needs as Azure variables named `repo.<NAME>` (visible to LATER steps of the same job).

    python3 scripts/ci/load_repo_variables.py --pipeline pr --repository owner/name

Only names listed in .ci/pipeline-contract.json (repositoryVariables) are considered, and only the pipeline's
`configVariables` are exported. A missing or empty required variable fails the step and names it; there is no
fallback value. Values are non-secret; the token is never printed.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

API = "https://api.github.com"
PAGE_SIZE = 30
DEFAULT_CONTRACT = Path(__file__).resolve().parents[2] / ".ci" / "pipeline-contract.json"


class LoaderError(Exception):
    pass


def fetch_variables(repository: str, token: str, opener=urllib.request.urlopen) -> dict[str, str]:
    variables: dict[str, str] = {}
    page = 1
    while True:
        request = urllib.request.Request(
            f"{API}/repos/{repository}/actions/variables?per_page={PAGE_SIZE}&page={page}",
            headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
                     "X-GitHub-Api-Version": "2022-11-28"})
        try:
            with opener(request, timeout=30) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            raise LoaderError(f"GitHub API returned HTTP {exc.code} while reading repository variables "
                              "(the token needs Actions variables read on this repository).") from None
        except (urllib.error.URLError, OSError, ValueError) as exc:
            raise LoaderError(f"Could not read repository variables from GitHub ({type(exc).__name__}).") from None
        batch = payload.get("variables") or []
        for item in batch:
            variables[item["name"]] = item.get("value", "")
        total = payload.get("total_count")
        if not batch or len(batch) < PAGE_SIZE or (total is not None and len(variables) >= total):
            return variables
        page += 1


def select(contract: dict, pipeline: str, available: dict[str, str]) -> dict[str, str]:
    pipelines = contract["pipelines"]
    if pipeline not in pipelines or "configVariables" not in pipelines[pipeline]:
        raise LoaderError(f"Unknown pipeline '{pipeline}'.")
    wanted = pipelines[pipeline]["configVariables"]
    outside = set(wanted) - set(contract["repositoryVariables"])
    if outside:
        raise LoaderError(f"Pipeline '{pipeline}' lists variables outside the contract: {sorted(outside)}")
    values = {name: available[name] for name in wanted if available.get(name)}
    missing = [name for name in wanted if name not in values]
    if missing:
        raise LoaderError("Required repository variable(s) missing or empty in GitHub: " + ", ".join(missing)
                          + ". Define them under Settings > Secrets and variables > Actions > Variables.")
    return values


def azure_escape(value: str) -> str:
    return value.replace("%", "%AZP25").replace("\r", "%0D").replace("\n", "%0A")


def main(argv: list[str] | None = None, opener=urllib.request.urlopen, out=None) -> int:
    out = out or sys.stdout
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pipeline", required=True)
    parser.add_argument("--repository", required=True, help="owner/name of the GitHub repository")
    parser.add_argument("--contract", default=str(DEFAULT_CONTRACT))
    args = parser.parse_args(argv)
    token = os.environ.get("GH_PAT", "")
    if not token:
        print("GH_PAT is required but empty or unset.", file=sys.stderr)
        return 1
    with open(args.contract, encoding="utf-8") as handle:
        contract = json.load(handle)
    try:
        values = select(contract, args.pipeline, fetch_variables(args.repository, token, opener))
    except LoaderError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    for name, value in values.items():
        out.write(f"##vso[task.setvariable variable=repo.{name}]{azure_escape(value)}\n")
    out.write(f"Loaded {len(values)} repository variable(s) for '{args.pipeline}': {', '.join(values)}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

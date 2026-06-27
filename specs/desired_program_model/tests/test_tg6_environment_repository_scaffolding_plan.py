from pathlib import Path


SPEC_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = SPEC_ROOT.parents[1]


def test_tg6_ticket_plan_orders_contract_before_scaffolds_and_lifecycle() -> None:
    plan = (SPEC_ROOT / "ticket_plan.yaml").read_text(encoding="utf-8")

    ordered_ids = ["TG-6A", "TG-6B", "TG-6C", "TG-6D", "TG-6E", "TG-6F"]
    positions = [plan.index(f"id: {ticket_id}") for ticket_id in ordered_ids]
    assert positions == sorted(positions)

    assert "id: TG-6G" not in plan
    assert "per_ticket_closeout_rule" in plan
    assert "functionality_graph_rule" in plan
    assert "exhaustive_environment_graph_rule" in plan
    assert "Close TG-6 spec workflow" not in plan
    assert "After every completed ticket" in plan
    assert "Document the environment repository contract and local k3d setup" in plan
    assert "scripts/scaffold-tf-env.py" in plan
    assert "scripts/scaffold-env.py" in plan
    assert "environmentRepositoryScaffoldLocal" in plan
    assert "environmentRepositoryScaffoldGithubAction" in plan
    assert "environmentRepositoryScaffoldAws" in plan
    assert "environmentRepositoryContractJbang" in plan
    assert "environmentRepositoryLocalLifecycle" in plan
    assert "environmentRepositoryGithubActionLifecycle" in plan
    assert "environmentRepositoryAwsLifecycle" in plan
    assert "deployment cases for missing cluster, existing cluster reuse, reset, explicit destroy, and skip-destroy" in plan
    assert "missing cluster deploy, existing cluster reuse without recreation, reset, explicit teardown, and skip teardown" in plan
    assert "temporary Git repository with git init/add/commit" in plan
    assert "normal CI cannot accidentally create" in plan
    assert "or delete cloud resources" in plan
    assert "scaffold output becomes the primary fixture for later lifecycle graphs" in plan


def test_tg6_desired_model_records_environment_repository_semantics() -> None:
    tla = (SPEC_ROOT / "TestGraph.tla").read_text(encoding="utf-8")
    manifest = (SPEC_ROOT / "spec_manifest.yaml").read_text(encoding="utf-8")
    desired_state = (SPEC_ROOT / "desired_state.yaml").read_text(encoding="utf-8")
    mc_cfg = (SPEC_ROOT / "MC.cfg").read_text(encoding="utf-8")

    for command in [
        "ScaffoldEnvironmentRepository",
        "ScaffoldEnvironmentTemplate",
        "ScaffoldLifecycleNodeTemplate",
        "VerifyEnvironmentContextRuntime",
        "GuardAwsBranchEnvironment",
        "SkipBranchEnvironmentReset",
        "SkipBranchEnvironmentDestroy",
    ]:
        assert f"@command {command}" in tla
        assert command in manifest
        assert command in desired_state

    for invariant in [
        "EnvironmentTemplatesRequireRepositoryScaffold",
        "LifecycleNodeTemplatesRequireRepositoryScaffold",
        "AwsProvisioningRequiresExplicitGuard",
        "SkippedResetEnvironmentsRemainProvisioned",
        "SkippedDestroyEnvironmentsRemainActive",
        "RuntimeContextVerificationIsTyped",
    ]:
        assert f"@invariant {invariant}" in tla
        assert invariant in manifest
        assert invariant in desired_state
        assert invariant in mc_cfg

    assert "NodeRuntimes = {UvRuntime, JbangRuntime}" in mc_cfg
    assert "LifecycleCommands = {DeployClusterCommand, ResetClusterCommand, DeleteClusterCommand}" in mc_cfg
    assert "AwsTargets = {AwsPreview}" in mc_cfg
    assert "EnvironmentTargets = {AwsPreview}" in mc_cfg
    assert "AwsBackends = {}" in mc_cfg
    assert "environmentRepositoryScaffoldGithubAction" in manifest
    assert "environmentRepositoryGithubActionLifecycle" in manifest
    assert "exhaustive_graph_policy" in desired_state


def test_tg6_functionality_tickets_require_test_graph_coverage() -> None:
    plan = (SPEC_ROOT / "ticket_plan.yaml").read_text(encoding="utf-8")

    for ticket_id in ["TG-6A", "TG-6B", "TG-6C", "TG-6D", "TG-6E", "TG-6F"]:
        start = plan.index(f"  - id: {ticket_id}")
        next_start = plan.find("\n  - id: ", start + 1)
        ticket = plan[start:] if next_start == -1 else plan[start:next_start]
        assert "graph_after_unit_pass:" in ticket
        assert "./scripts/run.py" in ticket
        assert "assertions:" in ticket


def test_tg6_current_model_remains_accepted_tg5_baseline_until_implementation() -> None:
    current_tla = (REPO_ROOT / "specs/current/TestGraph.tla").read_text(encoding="utf-8")
    current_manifest = (REPO_ROOT / "specs/current/spec_manifest.yaml").read_text(encoding="utf-8")
    program_tla = (REPO_ROOT / "specs/program_model/TestGraph.tla").read_text(encoding="utf-8")

    assert current_tla == program_tla
    assert "equivalent_to_accepted_program_model" in current_manifest
    assert "ScaffoldEnvironmentRepository" not in current_tla
    assert "GuardAwsBranchEnvironment" not in current_tla

----------------------------- MODULE TestGraph -----------------------------
EXTENDS Naturals, FiniteSets, Sequences, TLC

\* Accepted whole-program model for the test-graph skill. The product is a
\* validation DAG runner, so graph definitions, script metadata, DSL overlays,
\* dependency resolution, node execution, context flow, and report emission are
\* program semantics. Package paths and command recipes live in the manifest.

CONSTANTS
  Graphs,
  Nodes,
  Packages,
  Smoke,
  AppRunning,
  UserSeeded,
  NetworkPingable,
  LoginSmoke,
  SourceNodes,
  ExplicitNodesForSmoke,
  UserSeededScriptDeps,
  LoginSmokeScriptDeps,
  NetworkPingableDslDeps,
  LoginSmokeDslDeps,
  NoReason

VARIABLES
  scaffolded,
  declared_graphs,
  explicit_nodes,
  described_nodes,
  overlays,
  resolved_nodes,
  planned_graphs,
  plan_docs,
  active_graphs,
  passed_nodes,
  terminal_nodes,
  envelopes,
  context_items,
  run_reports,
  package_catalog,
  result

vars ==
  << scaffolded, declared_graphs, explicit_nodes, described_nodes, overlays,
     resolved_nodes, planned_graphs, plan_docs, active_graphs, passed_nodes,
     terminal_nodes, envelopes, context_items, run_reports, package_catalog,
     result >>

AvailableFor(g) ==
  IF g = Smoke THEN ExplicitNodesForSmoke ELSE {}

ScriptDepsOf(n) ==
  CASE n = UserSeeded -> UserSeededScriptDeps
    [] n = LoginSmoke -> LoginSmokeScriptDeps
    [] OTHER -> {}

DslDepsOf(g, n) ==
  CASE /\ g = Smoke
       /\ n = NetworkPingable -> NetworkPingableDslDeps
    [] /\ g = Smoke
       /\ n = LoginSmoke -> LoginSmokeDslDeps
    [] OTHER -> {}

MergedDeps(g, n) == ScriptDepsOf(n) \cup DslDepsOf(g, n)

GraphDependencyClosed(g, ns) ==
  /\ ns \subseteq SourceNodes
  /\ explicit_nodes[g] \subseteq ns
  /\ \A n \in ns:
      MergedDeps(g, n) \subseteq ns

Init ==
  /\ scaffolded = FALSE
  /\ declared_graphs = {}
  /\ explicit_nodes = [g \in Graphs |-> {}]
  /\ described_nodes = {}
  /\ overlays = [g \in Graphs |-> {}]
  /\ resolved_nodes = [g \in Graphs |-> {}]
  /\ planned_graphs = {}
  /\ plan_docs = {}
  /\ active_graphs = {}
  /\ passed_nodes = [g \in Graphs |-> {}]
  /\ terminal_nodes = [g \in Graphs |-> {}]
  /\ envelopes = [g \in Graphs |-> {}]
  /\ context_items = [g \in Graphs |-> {}]
  /\ run_reports = {}
  /\ package_catalog = {}
  /\ result = [accepted |-> TRUE, reason |-> NoReason]

\* @command ScaffoldProject
\* @result WorkflowResult
\* @port TestGraphProgramPort.scaffold_project
ScaffoldProject ==
  /\ scaffolded = FALSE
  /\ scaffolded' = TRUE
  /\ package_catalog' = Packages
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << declared_graphs, explicit_nodes, described_nodes, overlays,
                  resolved_nodes, planned_graphs, plan_docs, active_graphs,
                  passed_nodes, terminal_nodes, envelopes, context_items,
                  run_reports >>

\* @command RegisterGraph
\* @result WorkflowResult
\* @port TestGraphProgramPort.register_graph
RegisterGraph(g) ==
  /\ scaffolded
  /\ g \in Graphs
  /\ g \notin declared_graphs
  /\ declared_graphs' = declared_graphs \cup {g}
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, explicit_nodes, described_nodes, overlays,
                  resolved_nodes, planned_graphs, plan_docs, active_graphs,
                  passed_nodes, terminal_nodes, envelopes, context_items,
                  run_reports, package_catalog >>

\* @command AddExplicitNode
\* @result WorkflowResult
\* @port TestGraphProgramPort.add_explicit_node
AddExplicitNode(g, n) ==
  /\ scaffolded
  /\ g \in declared_graphs
  /\ g \notin planned_graphs
  /\ n \in AvailableFor(g)
  /\ explicit_nodes' = [explicit_nodes EXCEPT ![g] = @ \cup {n}]
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, described_nodes, overlays,
                  resolved_nodes, planned_graphs, plan_docs, active_graphs,
                  passed_nodes, terminal_nodes, envelopes, context_items,
                  run_reports, package_catalog >>

\* @command DescribeNode
\* @result WorkflowResult
\* @port TestGraphProgramPort.describe_node
DescribeNode(n) ==
  /\ scaffolded
  /\ n \in SourceNodes
  /\ described_nodes' = described_nodes \cup {n}
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, overlays,
                  resolved_nodes, planned_graphs, plan_docs, active_graphs,
                  passed_nodes, terminal_nodes, envelopes, context_items,
                  run_reports, package_catalog >>

\* @command ApplyDslOverlay
\* @result WorkflowResult
\* @port TestGraphProgramPort.apply_dsl_overlay
ApplyDslOverlay(g, n) ==
  /\ scaffolded
  /\ g \in declared_graphs
  /\ g \notin planned_graphs
  /\ n \in explicit_nodes[g]
  /\ DslDepsOf(g, n) /= {}
  /\ overlays' = [overlays EXCEPT ![g] = @ \cup {n}]
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, described_nodes,
                  resolved_nodes, planned_graphs, plan_docs, active_graphs,
                  passed_nodes, terminal_nodes, envelopes, context_items,
                  run_reports, package_catalog >>

\* @command ResolveNode
\* @result WorkflowResult
\* @port TestGraphProgramPort.resolve_node
ResolveNode(g, n) ==
  /\ scaffolded
  /\ g \in declared_graphs
  /\ g \notin planned_graphs
  /\ n \in described_nodes
  /\ n \in SourceNodes
  /\ (n \in explicit_nodes[g]
      \/ \E m \in explicit_nodes[g] \cup resolved_nodes[g]:
           n \in MergedDeps(g, m))
  /\ resolved_nodes' = [resolved_nodes EXCEPT ![g] = @ \cup {n}]
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, described_nodes,
                  overlays, planned_graphs, plan_docs, active_graphs,
                  passed_nodes, terminal_nodes, envelopes, context_items,
                  run_reports, package_catalog >>

\* @command PlanGraph
\* @result WorkflowResult
\* @port TestGraphProgramPort.plan_graph
PlanGraph(g) ==
  /\ scaffolded
  /\ g \in declared_graphs
  /\ g \notin planned_graphs
  /\ explicit_nodes[g] /= {}
  /\ GraphDependencyClosed(g, resolved_nodes[g])
  /\ \A n \in resolved_nodes[g]: n \in described_nodes
  /\ planned_graphs' = planned_graphs \cup {g}
  /\ plan_docs' = plan_docs \cup {g}
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, described_nodes,
                  overlays, resolved_nodes, active_graphs, passed_nodes,
                  terminal_nodes, envelopes, context_items, run_reports,
                  package_catalog >>

\* @command StartRun
\* @result WorkflowResult
\* @port TestGraphProgramPort.start_run
StartRun(g) ==
  /\ scaffolded
  /\ g \in planned_graphs
  /\ g \notin active_graphs
  /\ active_graphs' = active_graphs \cup {g}
  /\ passed_nodes' = [passed_nodes EXCEPT ![g] = {}]
  /\ terminal_nodes' = [terminal_nodes EXCEPT ![g] = {}]
  /\ envelopes' = [envelopes EXCEPT ![g] = {}]
  /\ context_items' = [context_items EXCEPT ![g] = {}]
  /\ run_reports' = run_reports \ {g}
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, described_nodes,
                  overlays, resolved_nodes, planned_graphs, plan_docs,
                  package_catalog >>

\* @command RunNodePass
\* @result NodeRunResult
\* @port TestGraphProgramPort.run_node_pass
RunNodePass(g, n) ==
  /\ scaffolded
  /\ g \in active_graphs
  /\ n \in resolved_nodes[g]
  /\ n \notin envelopes[g]
  /\ MergedDeps(g, n) \subseteq passed_nodes[g]
  /\ passed_nodes' = [passed_nodes EXCEPT ![g] = @ \cup {n}]
  /\ envelopes' = [envelopes EXCEPT ![g] = @ \cup {n}]
  /\ context_items' = [context_items EXCEPT ![g] = @ \cup {n}]
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, described_nodes,
                  overlays, resolved_nodes, planned_graphs, plan_docs,
                  active_graphs, terminal_nodes, run_reports, package_catalog >>

\* @command RunNodeTerminal
\* @result NodeRunResult
\* @port TestGraphProgramPort.run_node_terminal
RunNodeTerminal(g, n) ==
  /\ scaffolded
  /\ g \in active_graphs
  /\ n \in resolved_nodes[g]
  /\ n \notin envelopes[g]
  /\ MergedDeps(g, n) \subseteq passed_nodes[g]
  /\ terminal_nodes' = [terminal_nodes EXCEPT ![g] = @ \cup {n}]
  /\ envelopes' = [envelopes EXCEPT ![g] = @ \cup {n}]
  /\ active_graphs' = active_graphs \ {g}
  /\ result' = [accepted |-> FALSE, reason |-> "NODE_NOT_PASSED"]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, described_nodes,
                  overlays, resolved_nodes, planned_graphs, plan_docs,
                  passed_nodes, context_items, run_reports, package_catalog >>

\* @command WriteInlineReport
\* @result WorkflowResult
\* @port TestGraphProgramPort.write_inline_report
WriteInlineReport(g) ==
  /\ scaffolded
  /\ g \in active_graphs
  /\ resolved_nodes[g] /= {}
  /\ resolved_nodes[g] \subseteq passed_nodes[g]
  /\ run_reports' = run_reports \cup {g}
  /\ active_graphs' = active_graphs \ {g}
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, described_nodes,
                  overlays, resolved_nodes, planned_graphs, plan_docs,
                  passed_nodes, terminal_nodes, envelopes, context_items,
                  package_catalog >>

\* @command RebuildReport
\* @result WorkflowResult
\* @port TestGraphProgramPort.rebuild_report
RebuildReport(g) ==
  /\ scaffolded
  /\ g \in declared_graphs
  /\ envelopes[g] /= {}
  /\ run_reports' = run_reports \cup {g}
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, described_nodes,
                  overlays, resolved_nodes, planned_graphs, plan_docs,
                  active_graphs, passed_nodes, terminal_nodes, envelopes,
                  context_items, package_catalog >>

\* @command CleanBuild
\* @result WorkflowResult
\* @port TestGraphProgramPort.clean_build
CleanBuild ==
  /\ scaffolded
  /\ active_graphs' = {}
  /\ passed_nodes' = [g \in Graphs |-> {}]
  /\ terminal_nodes' = [g \in Graphs |-> {}]
  /\ envelopes' = [g \in Graphs |-> {}]
  /\ context_items' = [g \in Graphs |-> {}]
  /\ run_reports' = {}
  /\ result' = [accepted |-> TRUE, reason |-> NoReason]
  /\ UNCHANGED << scaffolded, declared_graphs, explicit_nodes, described_nodes,
                  overlays, resolved_nodes, planned_graphs, plan_docs,
                  package_catalog >>

NoOp ==
  UNCHANGED vars

Next ==
  \/ ScaffoldProject
  \/ \E g \in Graphs:
      RegisterGraph(g)
  \/ \E g \in Graphs, n \in Nodes:
      AddExplicitNode(g, n)
  \/ \E n \in Nodes:
      DescribeNode(n)
  \/ \E g \in Graphs, n \in Nodes:
      ApplyDslOverlay(g, n)
  \/ \E g \in Graphs, n \in Nodes:
      ResolveNode(g, n)
  \/ \E g \in Graphs:
      PlanGraph(g)
  \/ \E g \in Graphs:
      StartRun(g)
  \/ \E g \in Graphs, n \in Nodes:
      RunNodePass(g, n)
  \/ \E g \in Graphs, n \in Nodes:
      RunNodeTerminal(g, n)
  \/ \E g \in Graphs:
      WriteInlineReport(g)
  \/ \E g \in Graphs:
      RebuildReport(g)
  \/ CleanBuild
  \/ NoOp

\* @invariant TypeInvariant
TypeInvariant ==
  /\ scaffolded \in BOOLEAN
  /\ declared_graphs \subseteq Graphs
  /\ explicit_nodes \in [Graphs -> SUBSET SourceNodes]
  /\ described_nodes \subseteq SourceNodes
  /\ overlays \in [Graphs -> SUBSET SourceNodes]
  /\ resolved_nodes \in [Graphs -> SUBSET SourceNodes]
  /\ planned_graphs \subseteq declared_graphs
  /\ plan_docs \subseteq planned_graphs
  /\ active_graphs \subseteq planned_graphs
  /\ passed_nodes \in [Graphs -> SUBSET SourceNodes]
  /\ terminal_nodes \in [Graphs -> SUBSET SourceNodes]
  /\ envelopes \in [Graphs -> SUBSET SourceNodes]
  /\ context_items \in [Graphs -> SUBSET SourceNodes]
  /\ run_reports \subseteq Graphs
  /\ package_catalog \subseteq Packages
  /\ result.accepted \in BOOLEAN

\* @invariant ExplicitNodesAreAvailable
ExplicitNodesAreAvailable ==
  \A g \in Graphs:
    explicit_nodes[g] \subseteq AvailableFor(g)

\* @invariant ScriptSpecsAreSourceOfTruth
ScriptSpecsAreSourceOfTruth ==
  described_nodes \subseteq SourceNodes

\* @invariant DependencyConstantsAreIndexed
DependencyConstantsAreIndexed ==
  /\ Smoke \in Graphs
  /\ {AppRunning, UserSeeded, NetworkPingable, LoginSmoke} = SourceNodes
  /\ ExplicitNodesForSmoke \subseteq SourceNodes
  /\ UserSeededScriptDeps \subseteq SourceNodes
  /\ LoginSmokeScriptDeps \subseteq SourceNodes
  /\ NetworkPingableDslDeps \subseteq SourceNodes
  /\ LoginSmokeDslDeps \subseteq SourceNodes

\* @invariant DslOverlaysOnlyAddDependencies
DslOverlaysOnlyAddDependencies ==
  \A g \in Graphs, n \in SourceNodes:
    ScriptDepsOf(n) \subseteq MergedDeps(g, n)

\* @invariant PlannedGraphsAreDependencyClosed
PlannedGraphsAreDependencyClosed ==
  \A g \in planned_graphs:
    GraphDependencyClosed(g, resolved_nodes[g])

\* @invariant RunsRespectDependencies
RunsRespectDependencies ==
  \A g \in Graphs:
    \A n \in passed_nodes[g] \cup terminal_nodes[g]:
      MergedDeps(g, n) \subseteq passed_nodes[g]

\* @invariant ContextContainsOnlyPassedPublishedData
ContextContainsOnlyPassedPublishedData ==
  \A g \in Graphs:
    context_items[g] \subseteq passed_nodes[g]

\* @invariant EveryAttemptGetsOneEnvelope
EveryAttemptGetsOneEnvelope ==
  \A g \in Graphs:
    passed_nodes[g] \cup terminal_nodes[g] \subseteq envelopes[g]

\* @invariant ReportsHaveEnvelopeEvidence
ReportsHaveEnvelopeEvidence ==
  \A g \in run_reports:
    envelopes[g] /= {}

\* @invariant PackageCatalogIsCompleteAfterScaffold
PackageCatalogIsCompleteAfterScaffold ==
  scaffolded => package_catalog = Packages

Spec ==
  Init /\ [][Next]_vars

=============================================================================

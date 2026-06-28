plugins {
    id("com.hayden.testgraphsdk.graph")
}

validationGraph {
    sourcesDir("sources")

    testGraph("rerunGraphJbang") {
        node("sources/RerunGraphJbang.py")
    }

    testGraph("rerunGraphUv") {
        node("sources/RerunGraphUv.py")
    }

    testGraph("runOnlyJbang") {
        node("sources/RunOnlyJbang.py")
    }

    testGraph("runOnlyUv") {
        node("sources/RunOnlyUv.py")
    }

    testGraph("branchEnvironmentWorkflow") {
        node("sources/BranchEnvironmentMarkersPresent.py")
        node("sources/Tg5EnvironmentRepositoryContract.py")
        node("sources/Tg5FutureWorkflowPlan.py")
        node("sources/Tg5DeployCdcIssueWorkflowRecord.py")
    }

    testGraph("generatedEnvironmentRepositoryFixture") {
        node("sources/Tg5GeneratedEnvironmentRepositoryFixture.py")
    }

    testGraph("environmentRepositoryContract") {
        node("sources/Tg5StableEnvironmentRepositoryFixture.py")
        node("sources/Tg5EnvironmentRepositoryProvision.py")
        node("sources/Tg5EnvironmentContextEnvKey.py")
        node("sources/Tg5EnvironmentContextEnvAll.py")
        node("sources/Tg5EnvironmentRepositoryReuse.py")
    }

    testGraph("branchEnvironmentReset") {
        node("sources/Tg5StableEnvironmentRepositoryFixture.py")
        node("sources/Tg5EnvironmentRepositoryProvision.py")
        node("sources/Tg5EnvironmentRepositoryDeploy.py")
        node("sources/Tg5EnvironmentRepositoryReset.py")
        node("sources/Tg5BranchEnvironmentResetMarkers.py")
    }

    testGraph("branchEnvironmentMergeDestroy") {
        node("sources/Tg5StableEnvironmentRepositoryFixture.py")
        node("sources/Tg5EnvironmentRepositoryProvision.py")
        node("sources/Tg5EnvironmentRepositoryDeploy.py")
        node("sources/Tg5EnvironmentRepositoryDestroy.py")
        node("sources/Tg5BranchEnvironmentDestroyMarkers.py")
    }

    testGraph("deployCdcIssueContract") {
        node("sources/Tg5DeployCdcIssueContract.py")
        node("sources/Tg5DeployCdcNoSdkCoupling.py")
        node("sources/Tg5DeployCdcIssueWorkflowRecord.py")
    }

    testGraph("environmentRepositoryDocumentation") {
        node("sources/Tg6EnvironmentRepositoryDocumentation.py")
    }

    testGraph("environmentRepositoryScaffoldLocal") {
        node("sources/Tg6EnvironmentRepositoryScaffoldLocal.py")
        node("sources/Tg6EnvironmentRepositoryProvision.py")
        node("sources/Tg6EnvironmentContextEnvKey.py")
        node("sources/Tg6EnvironmentContextEnvAll.py")
        node("sources/Tg6EnvironmentRepositoryReuse.py")
    }

    testGraph("environmentRepositoryScaffoldGithubAction") {
        node("sources/Tg6EnvironmentRepositoryScaffoldGithubAction.py")
    }

    testGraph("environmentRepositoryScaffoldAws") {
        node("sources/Tg6EnvironmentRepositoryScaffoldAws.py")
    }
}

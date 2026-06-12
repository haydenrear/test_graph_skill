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
}

plugins {
    id("com.hayden.testgraphsdk.graph")
}

validationGraph {
    sourcesDir("sources")

    testGraph("selfValidation") {
        node("sources/RerunBuildFlow.py")
    }
}

package top.colter.dynamic.data

public data class DynamicAdditional(
    val card: DynamicAdditionalCard? = null,
    val vote: DynamicAdditionalVote? = null,
    val tags: DynamicAdditionalTags? = null,
)

public data class DynamicAdditionalCard(
    val id: String,
)

public data class DynamicAdditionalVote(
    val id: String,
)

public data class DynamicAdditionalTags(
    val id: String,
)
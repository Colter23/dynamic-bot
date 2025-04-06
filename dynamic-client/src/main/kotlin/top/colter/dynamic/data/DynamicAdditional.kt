package top.colter.dynamic.data

import kotlinx.serialization.Serializable

@Serializable
public data class DynamicAdditional(
    val card: DynamicAdditionalCard? = null,
    val vote: DynamicAdditionalVote? = null,
    val tags: DynamicAdditionalTags? = null,
)

@Serializable
public data class DynamicAdditionalCard(
    val id: String,
)

@Serializable
public data class DynamicAdditionalVote(
    val id: String,
)

@Serializable
public data class DynamicAdditionalTags(
    val id: String,
)
package com.myapplication.common.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class AnalysisResult(
    @SerialName("ai_probability")
    val aiProbability: Double,

    @SerialName("conclusion")
    val conclusion: String
)

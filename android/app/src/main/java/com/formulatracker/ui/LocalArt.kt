package com.formulatracker.ui

import com.formulatracker.R
import java.text.Normalizer
import java.util.Locale

internal fun circuitImageRes(circuitName: String): Int? {
    return CIRCUIT_ART[circuitArtKey(circuitName).orEmpty()]
}

internal fun teamLogoRes(teamName: String?): Int? {
    if (teamName.isNullOrBlank()) {
        return null
    }
    return TEAM_ART[teamArtKey(teamName).orEmpty()]
}

internal fun circuitArtKey(circuitName: String): String? {
    val slug = artSlug(circuitName)
    val key = CIRCUIT_ALIASES[slug] ?: slug
    return key.takeIf { it in CIRCUIT_ART }
}

internal fun teamArtKey(teamName: String): String? {
    val slug = artSlug(teamName)
    val key = TEAM_ALIASES[slug] ?: slug
    return key.takeIf { it in TEAM_ART }
}

internal fun artSlug(name: String): String {
    val stripped = Normalizer.normalize(name, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
    return stripped
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
}

private val CIRCUIT_ALIASES = mapOf(
    // Jolpica spells the Qatar circuit Lusail. The photo file uses Losail.
    "lusail_international_circuit" to "losail_international_circuit",
)

private val TEAM_ALIASES = mapOf(
    "alpine_f1_team" to "alpine",
    "haas_f1_team" to "haas",
    "rb_f1_team" to "rb",
    "kick_sauber" to "sauber",
    "cadillac_f1_team" to "cadillac",
)

private val CIRCUIT_ART = mapOf(
    "albert_park_grand_prix_circuit" to R.drawable.albert_park_grand_prix_circuit,
    "autodromo_enzo_e_dino_ferrari" to R.drawable.autodromo_enzo_e_dino_ferrari,
    "autodromo_hermanos_rodriguez" to R.drawable.autodromo_hermanos_rodriguez,
    "autodromo_jose_carlos_pace" to R.drawable.autodromo_jose_carlos_pace,
    "autodromo_nazionale_di_monza" to R.drawable.autodromo_nazionale_di_monza,
    "bahrain_international_circuit" to R.drawable.bahrain_international_circuit,
    "baku_city_circuit" to R.drawable.baku_city_circuit,
    "circuit_de_barcelona_catalunya" to R.drawable.circuit_de_barcelona_catalunya,
    "circuit_de_monaco" to R.drawable.circuit_de_monaco,
    "circuit_de_spa_francorchamps" to R.drawable.circuit_de_spa_francorchamps,
    "circuit_gilles_villeneuve" to R.drawable.circuit_gilles_villeneuve,
    "circuit_of_the_americas" to R.drawable.circuit_of_the_americas,
    "circuit_park_zandvoort" to R.drawable.circuit_park_zandvoort,
    "hungaroring" to R.drawable.hungaroring,
    "jeddah_corniche_circuit" to R.drawable.jeddah_corniche_circuit,
    "las_vegas_strip_street_circuit" to R.drawable.las_vegas_strip_street_circuit,
    "losail_international_circuit" to R.drawable.losail_international_circuit,
    "madring" to R.drawable.madring,
    "marina_bay_street_circuit" to R.drawable.marina_bay_street_circuit,
    "miami_international_autodrome" to R.drawable.miami_international_autodrome,
    "red_bull_ring" to R.drawable.red_bull_ring,
    "sepang_international_circuit" to R.drawable.sepang_international_circuit,
    "shanghai_international_circuit" to R.drawable.shanghai_international_circuit,
    "silverstone_circuit" to R.drawable.silverstone_circuit,
    "suzuka_circuit" to R.drawable.suzuka_circuit,
    "yas_marina_circuit" to R.drawable.yas_marina_circuit,
)

private val TEAM_ART = mapOf(
    "alpine" to R.drawable.alpine,
    "aston_martin" to R.drawable.aston_martin,
    "audi" to R.drawable.audi,
    "cadillac" to R.drawable.cadillac,
    "ferrari" to R.drawable.ferrari,
    "haas" to R.drawable.haas,
    "mclaren" to R.drawable.mclaren,
    "mercedes" to R.drawable.mercedes,
    "rb" to R.drawable.rb,
    "red_bull" to R.drawable.red_bull,
    "sauber" to R.drawable.sauber,
    "williams" to R.drawable.williams,
)
